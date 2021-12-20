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
   [sm_async_api.utils.macro :refer [_case get-channel-id resp-data]]
   [sm_async_api.utils.dispatcher :as dispatcher :refer [fetch-marker
                                                         command-exit]]
   [sm_async_api.enum.task_result :as tr]
   [taoensso.timbre.appenders.core :as appenders]
   [sm_async_api.task.writers :as tw]
   [sm_async_api.task.sync_pusher :as sp]
   [sm_async_api.dal.task :as dal-t]))

;(timbre/merge-config! {:appenders  {:println {:enabled? true}}})
;(timbre/merge-config!  {:appenders {:spit (appenders/spit-appender {:fname "log/dispatcher.log"})}})
;(timbre/set-level! :debug)

(def ^:const default-thread-count 1)

(def ^:const default-chank-size 10)

;(def ^:const fetch-marker "FETCH")

(def ^:const default-new-task-waiting 2000)

(def ^:const default-max-retry-waiting 15); 15000)

(def ^:const default-server-not-available-waiting 10) ;15000)

(def ^:const default-max-retry-count 1)

(defonce online-pushers (atom {}))

;(def ^:const command-exit "EXIT")


(defn- exit-reader [^String id]
  (if (some? (@online-pushers id))
    (do
      (swap! online-pushers update-in [id :reader-exited] (fn [_] true))
      (reportf  "Reader %s exited." id))
    (error (format "Reader %s not found in readers list: %s" id @online-pushers))))

(defn task-reader [in out
                   chank-size
                   prefetch-marker-position
                   ^String id
                   ^String condition
                   reader]
  (debug id ":Waiting for command.")

  (let [new-task-waiting  (or (config/get-executors-globals :new-task-waiting)  default-new-task-waiting)]
    (a/thread
      (loop [input (<!! in)]
      ;(debug id ": recived command - " input)
        (when-not (or (nil? input) (= command-exit input))
          (let [result-set (reader id chank-size condition)]
            (Thread/sleep 5); avoid double reading 
            #_(timbre/with-merged-config
                {:println {:enabled? false}
                 :appenders {:spit (appenders/spit-appender {:fname (str "log/" id ".log")})}}
                (if (empty? result-set)
                  (debug id ":zero fetch")
                  (debug id ":fetched items "
                         (reduce #(str %1 ":"  (:req_id %2))  "" result-set) ":")))
            (if (empty?  result-set)
              (do (debug id ": no actions available, fall sleep")
                  (Thread/sleep new-task-waiting)
                  (>!! in  fetch-marker))
              (if (zero? prefetch-marker-position)
                (do
                  (doseq [result result-set]
                    (debug id ":Task queued:" result)
                    (>!! out result))
                  (>!! out  fetch-marker))
                #_(when-not (>
                           (reduce (fn [pos result] 
                                     (when (= pos prefetch-marker-position)
                                                      (>!! out  fetch-marker))
                                     (>!! out result)
                                     (inc pos)) 0 result-set) prefetch-marker-position) 
                  (>!! out  fetch-marker))
                (loop [result-set result-set
                         pos 0]
                    (let [f (first result-set)]
                      (if (nil? f)
                        (when-not (> pos prefetch-marker-position) (>!! out  fetch-marker))
                        (do
                          (debug id ":Task queued:" f)
                          (>!! out f)
                          (when (= pos prefetch-marker-position) (>!! out  fetch-marker))
                          (recur (rest result-set) (inc pos)))))))))
          (recur (<!! in))))
      (exit-reader id))))

(defn- exit-thread [^String id local-channel]
  (when (some? local-channel) (close! local-channel))
  (let [pusher-id ((str/split id #"\/" 2) 0)]
    (if (some? (@online-pushers pusher-id))
      (reportf  "Thread %s exited, %s threads left." id
                (((swap! online-pushers update-in [pusher-id :threads] dec)
                  pusher-id)  :threads))
      (error (AssertionError. (format "Thread %s not found in thread list: %s" pusher-id @online-pushers))))))

(defn- write-channel-callback-factory [channel]
  (fn [resp]
    (timbre/with-merged-config
      {:appenders {:println {:enabled? false}
                   :spit (appenders/spit-appender {:fname "log/cbk-channel.log"})}}
      (debug (resp-data resp) "write-to channel " (get-channel-id channel)))
    (>!! channel resp)))

(defn task-executor-fabric [async?]
  (let [executor-globals (config/get-executors-globals)
        max-retry-waiting (or (:max-retry-waiting executor-globals) default-max-retry-waiting)
        server-not-available-waiting (or (:server-not-available-waiting executor-globals) default-server-not-available-waiting)
        max-retry-count (or (:max-retry-waiting executor-globals) default-max-retry-count)]
    (reportf "Task executors global parameters: max-retry-count %d, \nmax-retry-waiting %d, \nserver-not-available-waiting %d"
             max-retry-count max-retry-waiting server-not-available-waiting)
    (fn [in out ^String id pusher]
      (let [local-channel (when async? (chan))
            write-channel-callback (when async? (write-channel-callback-factory local-channel))]
        (timbre/with-merged-config
          {:appenders {:println {:enabled? false}
                       :spit (appenders/spit-appender {:fname (str "log/" ((str/split id #"/" 2) 0) ".log")})}}
          (reportf "%s:Task executor configured for %s mode" id (if (nil? local-channel) "sync" "async"))
          (go
            (loop [input (<! in)
                   retries 0]
              (if (or (nil? input) (string? input))
                (if (= input fetch-marker)
                  (do (>! out  fetch-marker) (recur  (<! in) 0))
                  (exit-thread id local-channel)) ; exit on any string exept "FETCH"

                (do (debug  id ":Run task:"  (input :req_id) "Retry:" retries)
                    (let [result (if async?
                                   (do (pusher input id write-channel-callback)
                                       (<! local-channel))
                                   (pusher input id))
                          result-code  (sp/processor  result id)]
                      (debug  id ":Task:"  (input :req_id) " channel" (get-channel-id local-channel) "->result" result "result code " result-code)
                      (_case  result-code

                              tr/NEXT-ACTION (recur (<! in) 0)

                              tr/RETRY-ACTION (if (> max-retry-count retries)
                                                (do
                                                  (debug id ":Task" (input :req_id) "retry " retries " delay started.")
                                                  (<! (timeout (* max-retry-waiting (rand))))
                                                  (recur input (inc retries)))
                                                (do
                                                  (warn id ":Retry count exceeded for " (input :req_id) ".Action rescheduled")
                                                  (@tw/action-rescheduler  (input :req_id) nil nil)
                                                  (recur (<! in) 0)))

                              tr/SERVER-NOT-AVAILABLE (do
                                                        (warnf "%s Server not available. Action %s rescheduled. Sleep for %sms"
                                                               id (input :req_id) server-not-available-waiting)
                                                        (@tw/action-rescheduler  (input :req_id) nil nil)
                                                        (<! (timeout server-not-available-waiting))
                                                        (recur (<! in) 0))

                              tr/EXIT-THREAD  (exit-thread id local-channel)

                              (do (error id ":Unknow processor responce for  " input)
                                  (recur (<! in) 0))))))))
          #_(when (some? local-channel) (close! local-channel)))))))


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
        (dal-t/user-lock-condition name)
        (dal-t/user-list-conditions (str/join "','" name))))

    :global-mode
    (let [exclude-list (build-exclude-list)]
      (when-not (empty? exclude-list)
        (if (= exclude-list :not-configured)
          dal-t/global-lock-condition-global-only
          (dal-t/global-lock-condition exclude-list))))

    :async-mode dal-t/async-lock-condition

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
    global-id (str (config/get-config :async-gateway-id) "::T-" local-id)]
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
                            config workers database]
  (run-pusher {:mode :user-mode
               :name  name
               :threads threads
               :chank-size chank-size}
              (if (string? name) (str "D::" name) (str "L::" (name 0)))
              (dal-t/task-reader-factory database)
              (pusher-factory :user-mode get-allowed config workers)
              task-executor))


(defn pusher-manager-run  []
  (let [{:keys [workers config database]}  @config/config
        pusher-factory (sp/get-pusher-factory (config  :async-pusher-enabled))
        task-executor  (task-executor-fabric (config  :async-pusher-enabled))]
    ;(send tw/result-writer tw/get-result-writer) ;replaced with delay in writer
    ;(send tw/action-rescheduler tw/get-action-rescheduler) ;replaced with delay in writer
    (infof "Configure pushers (async-mode: %s) ..." (config  :async-pusher-enabled))
    (reset! online-pushers
            (conj
             (when (workers :dedicated-enabled)
               (into {} (map run-dedicated-pusher
                             (workers :dedicated)
                             (repeat pusher-factory)
                             (repeat task-executor)
                             (repeat config)
                             (repeat workers)
                             (repeat database))))
             (when (workers :global-enabled)
               (run-pusher {:mode :global-mode
                            :threads (workers :global-threads)
                            :chank-size (workers :global-chank-size)}
                           "G::Global"
                           (dal-t/task-reader-factory database)
                           (pusher-factory :global-mode (workers :global-get-allowed) config workers)
                           task-executor))
             (when (workers :async-enabled)
               (run-pusher {:mode :async-mode
                            :threads (workers :async-threads)
                            :chank-size (workers :async-chank-size)}
                           "A::Async"
                           (dal-t/task-reader-factory database)
                           (pusher-factory :async-mode false config workers)
                           task-executor))))))

#_(defn pusher-manager-kick [[_ {:keys [reader-control]}]]
    (>!! reader-control  fetch-marker))
(def pusher-manager-kick dispatcher/kick)

#_(defn pusher-manager-kill [[_ {:keys [reader-control task-buffer]}]]
    (close! reader-control)
    (close! task-buffer))

(def pusher-manager-kill dispatcher/kill)

#_(defn pusher-manager-kill-reader [[_ {:keys [reader-control]}]]
    (close! reader-control))
(def pusher-manager-kill-reader dispatcher/kill-reader)

#_(defn pusher-manager-kill-pusher [[_ {:keys [task-buffer]}]]
    (close! task-buffer))
(def pusher-manager-kill-pusher dispatcher/kill-pusher)


#_(defn pusher-manager-kill-thread [[_ {:keys [task-buffer]}]]
    (>!!   task-buffer command-exit))
(def pusher-manager-kill-thread dispatcher/kill-thread)



#_(defn pusher-manager-do-all [action]
    (doseq [pusher @online-pushers] (action pusher)))

(defmacro pusher-manager-do-all [action]
  `(dispatcher/do-all online-pushers ~action))

(defn pusher-manager-get-pusher [pusher-id]
  (@online-pushers pusher-id))



(defn start-pushers
  ([] (start-pushers true))
  ([kick?]
   (if (some? (pusher-manager-run))
     (when kick? (pusher-manager-do-all pusher-manager-kick))
     1)))

(defn stop-pushers
  ([] (dispatcher/stop online-pushers 10))
  ([max-wait-time] (dispatcher/stop online-pushers max-wait-time)))
#_(defn stop-pushers
    ([] (stop-pushers 10))
    ([max-wait-time]
     (pusher-manager-do-all pusher-manager-kill-reader)
     (loop [t  max-wait-time]
       (let [active-readers (reduce count-readers 0 @online-pushers)]
         (if (zero? active-readers)
           (info "No more readers active")
           (if  (> t 0)
             (do
               (infof "$s readers sill running...")
               (Thread/sleep 1000)
               (recur (dec t)))
             (report "Readers grace period expired. %s readers still running." active-readers)))))

     (pusher-manager-do-all pusher-manager-kill-pusher)
     (loop [t max-wait-time]
       (let [active-threads (reduce count-threads 0 @online-pushers)]
         (if (zero? active-threads)
           (info "No more pusher threads active.")
           (if  (> t 0)
             (do
               (infof "$s pusher threads sill running...")
               (Thread/sleep 1000)
               (recur (dec t)))
             (report "Pusher grace period expired. %s pusher threads still running." active-threads)))))))
