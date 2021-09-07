#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.task.sync_dispatcher
  (:require
   [clojure.string :as str]
   [clojure.core.async
    :as a
    :refer [>! <! >!! <!! go go-loop chan buffer sliding-buffer close! thread
            alts! alts!! timeout]]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]
   [sm_async_api.config :as config]
   [sm_async_api.utils.macro :refer [_case]]
   [sm_async_api.enum.task_result :as tr]
   [taoensso.timbre.appenders.core :as appenders]
   [sm_async_api.task.writers :as tw]
   [sm_async_api.task.sync_pusher :as sp]
   [sm_async_api.dal :as dal]))

;(timbre/merge-config! {:appenders  {:println {:enabled? true}}})
;(timbre/merge-config!  {:appenders {:spit (appenders/spit-appender {:fname "log/dispatcher.log"})}})
;(timbre/set-level! :debug)

(def default-thread-count 1)

(def default-chank-size 10)

(def fetch-marker "FETCH")

(def new-task-wating 2000)

(def max-retry-wating 15000)

(defonce online-pushers (atom {}))

(def command-exit "EXIT")

(defn task-reader [in out
                   chank-size
                   prefetch-marker-position
                   ^String id
                   ^String condition
                   reader]
  (debug id ":Waiting for command.")
  (a/thread
    (loop [input (<!! in)]
      (debug id ": recived command - " input)
      (when-not (or (nil? input) (= command-exit input))
        (let [result-set (reader id chank-size condition)]
          (Thread/sleep 5); avoid double reading 
          (debug id ":" (reduce #(str %1 "||" %2) "" result-set))
          (if (empty?  result-set)
            (do (debug id ": no actions available, fall sleep")
                (Thread/sleep new-task-wating)
                (>!! in  fetch-marker))
            (if (zero? prefetch-marker-position)
              (do
                (doseq [result result-set] (>!! out result))
                (>!! out  fetch-marker))
              (loop [result-set result-set
                     pos 0]
                (let [f (first result-set)]
                  (if (nil? f)
                    (when-not (> pos prefetch-marker-position) (>!! out  fetch-marker))
                    (do
                      ;(debug "cmd " (f :req_id))
                      (>!! out f)
                      (when (= pos prefetch-marker-position) (>!! out  fetch-marker))
                      (recur (rest result-set) (inc pos)))))))))
        (recur (<!! in)))))
  (info id ":Task reader exited."))

(defn exit-thread [^String id]
  (let [pusher-id ((str/split id #"\/" 2) 0)]
    (when (some? (@online-pushers pusher-id))
      (info (format  "Thread %s exited, %s threads left" id
                     (((swap! online-pushers update-in [pusher-id :threads] dec)
                       pusher-id) :threads))))))

(defn- write-channel-callback-factory [channel]
  (fn [resp]
    (timbre/with-merged-config
      {:appenders {:spit (appenders/spit-appender {:fname "log/cbk-channel.log"})}}
      (debug resp))
    (>!! channel resp)))

(defn task-executor-fabric [async?]
  (let [local-channel (when async? (chan))
        write-channel-callback (when async? (write-channel-callback-factory local-channel))]
    (fn [in out ^String id pusher]
      (reportf "%s:Task executor configured for %s mode" id (if (nil? local-channel) "sync" "async"))
      (go
        ;(let [get-task  ;(get-task-fabric async? pusher  write-channel-callback local-channel) ]
        (loop [input (<! in)]
          (_case input
                 nil   (exit-thread id) ;(log (format  "Channel is closed.Thread %s exited " id))

                 command-exit (exit-thread id)

                 fetch-marker (do (>! out  fetch-marker) (recur  (<! in)))

                 (do (debug  id ":Run task:"  (input :req_id))
                     (_case  (sp/processor (if async?
                                             (do (pusher input id write-channel-callback)
                                                 (<! local-channel))
                                             (pusher input id)) id)
                             tr/NEXT-ACTION (recur (<! in))
                             tr/RETRY-ACTION (do
                                               (<! (timeout (* max-retry-wating (rand))))
                                               (recur input))
                             tr/EXIT-THREAD  (exit-thread id)
                             (do (error "Unknow processor responce for  " input)
                                 (recur (<! in)))))));)
        (close! local-channel)))));)


(defn build-exclude-list []
  (if (config/get-workers :dedicated)
    (str/join "','" (reduce #(if (empty? %2)  %1 (conj %1 %2))
                            nil (flatten
                                 (reduce #(conj %1 (:name %2)) nil  (config/get-workers :dedicated)))))
    :not-configured))

(defn build-condition [{:keys [name  mode]}]
  (case mode
    :user-mode
    (when-not (empty? name)
      (if (string? name)
        (dal/user-lock-conditions name)
        (dal/user-list-conditions (str/join "','" name))))

    :global-mode
    (let [exclude-list (build-exclude-list)]
      (when-not (empty? exclude-list)
        (if (= exclude-list :not-configured)
          dal/global-lock-condition-global-only
          (dal/global-lock-condition exclude-list))))

    :async-mode dal/async-lock-condition

    nil))



(defn run-pusher
  [params local-id reader pusher task-executor]
  (let
   [chank-size  (or (params :chank-size) default-chank-size)
    threads  (or (params :threads) default-thread-count)
    prefetch-marker-position  (if  (= (config/get-config :prefetch-enabled) true)
                                (- chank-size (quot chank-size 2))
                                0)
    channel-size (+ chank-size (quot chank-size 2) threads)
    task-buffer (chan  channel-size)
    reader-control (chan (sliding-buffer 2))
    condition  (build-condition params)
    global-id (str (config/get-config :async_gateway_id) "-" local-id)]
    (if (nil? condition)
      (fatal "Incorrect condition for pusher %s. Supplied parameters %s. Pusher skipped."
             global-id params)
      (do
        (info "Configure pusher: local-id" local-id "params " params "channel size " channel-size)
        (task-reader reader-control
                     task-buffer
                     chank-size
                     prefetch-marker-position
                     global-id
                     condition
                     reader)
        (dotimes [i threads]
          (task-executor task-buffer
                         reader-control
                         (str global-id "/" i)
                         pusher))

        {global-id
         {:task-buffer task-buffer
          :reader-control reader-control
          :threads threads
          :condition condition
          :mode (params :mode)}}))));)


(defn run-dedicated-pusher [{:keys [name threads chank-size get-allowed]}
                            pusher-factory task-executor
                            config workers]
  (run-pusher {:mode :user-mode
               :name  name
               :threads threads
               :chank-size chank-size}
              (if (string? name) (str "D::" name) (str "L::" (name 0)))
              dal/task-reader
              (pusher-factory :user-mode get-allowed config workers)
              task-executor))
              

(defn pusher-manager-run  []
  (let [{:keys [workers config]}  @config/config
        pusher-factory (sp/get-pusher-factory (config  :async-pusher-enabled))
        task-executor  (task-executor-fabric (config  :async-pusher-enabled))]
    (send tw/result-writer tw/get-result-writer)
    (send tw/action-rescheduler tw/get-action-rescheduler)
    (infof "Configure pushers (async-mode: %s) ..." (config  :async-pusher-enabled))
    (reset! online-pushers
            (conj
             (when (workers :dedicated-enabled)
               (into {} (map run-dedicated-pusher
                             (workers :dedicated)
                             (repeat pusher-factory)
                             (repeat task-executor)
                             (repeat config)
                             (repeat workers))))
             (when (workers :global-enabled)
               (run-pusher {:mode :global-mode
                            :threads (workers :global-threads)
                            :chank-size (workers :global-chank-size)}
                           "G::Global"
                           dal/task-reader
                           (pusher-factory :global-mode (workers :global-get-allowed) config workers)
                           task-executor))
             (when (workers :async-enabled)
               (run-pusher {:mode :async-mode
                            :threads (workers :async-threads)
                            :chank-size (workers :async-chank-size)}
                           "A::Async"
                           dal/task-reader
                           (pusher-factory :global-mode false config workers)
                           task-executor)))))
  (info "Pushers are configured."))

(defn pusher-manager-kick [[_ {:keys [reader-control]}]]
  (>!! reader-control  fetch-marker))

(defn pusher-manager-kill [[_ {:keys [reader-control task-buffer]}]]
  (close! reader-control)
  (close! task-buffer))

(defn pusher-manager-kill-thread [[_ {:keys [task-buffer]}]]
  (>!!   task-buffer command-exit))


(defn pusher-manager-kill-all-threads [[_ {:keys [task-buffer threads]}]]
  (dotimes [_ threads] (>!!   task-buffer command-exit)))

(defn print-status [[name {:keys [mode threads condition]}]]
  (println "Name: "  name " Mode " mode " Threads " threads)
  (println "      Condition:" condition))

(defn pusher-manager-do-all [action]
  (doseq [pusher @online-pushers] (action pusher)))

(defn pusher-manager-get-pusher [pusher-id]
  (@online-pushers pusher-id))

