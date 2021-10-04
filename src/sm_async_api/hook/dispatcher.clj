#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.hook.dispatcher
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
   [sm_async_api.hook.globals :as hg]
   [sm_async_api.hook.pusher :refer [message-sender-factory]]
   [sm_async_api.hook.reader :refer [message-reader]]
   [sm_async_api.dal.hook :as dal-h]))



(defn build-exclude-list []
  (if (config/get-messangers :dedicated)
    (str/join "','" (reduce #(if (empty? %2)  %1 (conj %1 %2))
                            nil (flatten
                                 (reduce #(conj %1 (:name %2)) nil  (config/get-messangers :dedicated)))))
    :not-configured))

(defn build-condition [{:keys [name  mode]}]
  (case mode
    :user-mode
    (when-not (empty? name)
      (if (string? name)
        (dal-h/user-lock-conditions name)
        (dal-h/user-list-conditions (str/join "','" name))))

    :global-mode
    (let [exclude-list (build-exclude-list)]
      (when-not (empty? exclude-list)
        (if (= exclude-list :not-configured)
          dal-h/global-lock-condition-global-only
          (dal-h/global-lock-condition exclude-list))))
    nil))


(defn run-messanger
  [params local-id reader message-sender]
  (let
   [chank-size  (or (params :chank-size) hg/default-chank-size)
    threads  (or (params :threads) hg/default-thread-count)
    prefetch-marker-position  (if  (= (config/get-config :prefetch-enabled) true)
                                (- chank-size (quot chank-size 2))
                                0)
    channel-size (+ chank-size (quot chank-size 2) threads)
    task-buffer (chan  channel-size)
    reader-control (chan (sliding-buffer 2))
    condition  (build-condition params)
    global-id (str (config/get-config :async_gateway_id) "::M-" local-id)]
    (if (nil? condition)
      (fatal "Incorrect condition for pusher %s. Supplied parameters %s. Pusher skipped."
             global-id params)
      (do
        (info "Configure pusher: local-id" local-id "params " params "channel size " channel-size)
        (message-reader reader-control
                        task-buffer
                        chank-size
                        prefetch-marker-position
                        global-id
                        condition
                        reader)
        (dotimes [i threads]
          (message-sender task-buffer
                          reader-control
                          (str global-id "/" i)))

        {global-id
         {:task-buffer task-buffer
          :reader-control reader-control
          :threads threads
          :condition condition
          :mode (params :mode)}}))))

(defn run-dedicated-messanger [{:keys [name threads chank-size ]}
                               message-sender               reader]
  (run-messanger {:mode :user-mode
                  :name  name
                  :threads threads
                  :chank-size chank-size}
                 (if (string? name) (str "D::" name) (str "L::" (name 0)))
                 reader
                 message-sender))

(defn messanger-manager-run  []
  (let [{:keys [messangers config database]}  @config/config
        reader (dal-h/message-reader-factory database)
        message-sender (message-sender-factory  (config  :async-pusher-enabled))]
    (infof "Configure messangers (async-mode: %s) ..." (config  :async-pusher-enabled))
    (reset! hg/online-messangers
            (conj
             (when (messangers :dedicated-enabled)
               (into {} (map run-dedicated-messanger
                             messangers
                             (messangers :dedicated)
                             (repeat message-sender))))
             (when (messangers :global-enabled)
               (run-messanger {:mode :global-mode
                               :threads (messangers :global-threads)
                               :chank-size (messangers :global-chank-size)}
                              "G::Global"
                              reader
                              message-sender)))))
  (info "Pushers are configured."))