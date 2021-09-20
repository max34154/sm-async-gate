(ns sm_async_api.task.sync_pusher
  {:clj-kondo/config  '{:linters {:unused-referred-var
                                  {:exclude {taoensso.timbre [log  trace  debug  info  warn  error  fatal  report
                                                              logf tracef debugf infof warnf errorf fatalf reportf
                                                              spy get-env]}}}}}
  (:require  [cheshire.core :as json]
             [org.httpkit.client :as http]
            ; [sm_async_api.utils.base64 :as b64]
             [taoensso.timbre :as timbre
              :refer [log  trace  debug  info  warn  error  fatal  report
                      logf tracef debugf infof warnf errorf fatalf reportf
                      spy get-env]]
             [taoensso.timbre.appenders.core :as appenders]
             ;[sm_async_api.config :as config]
             [sm_async_api.session :as session]
             [sm_async_api.utils.macro :refer [_case]]
             [sm_async_api.enum.task_result :as tr]
             [sm_async_api.task.process_result :as result]))


(defn- fill-async-request [{:keys [action parameters schedule_name execution_retries retry_interval]}]
  (json/generate-string     {:action action
                             :parameters parameters
                             :execution
                             {:mode "scheduleOnly"
                              :sheduler schedule_name
                              :retries execution_retries
                              :retiryInterval  retry_interval}}))

#_(defmacro get-http-method [action]
    `(if (= (~action :action) "get")  :get :post))

#_(defmacro post-url [action base-url]
    `(str ~base-url (~action :service) "/" (~action :subject) "/" (~action :action)))

#_(defmacro get-url [action base-url]
    `(str ~base-url  (~action :service) "/" (~action :subject)))


#_(defmacro build-url [action base-url]
    `(if (= (~action :action) "get")
       (get-url ~action ~base-url)
       (post-url ~action ~base-url)))

#_(defmacro opts [action thread mode get-allowed base-url async-credentials global-credentials async-action-url]
    `{:mode ~mode
      :url ~(if (= mode :async-mode)  async-action-url
                (if get-allowed  `(if (= (~action :action) "get")
                                    (str ~base-url  (~action :service) "/" (~action :subject))
                                    (str ~base-url (~action :service) "/" (~action :subject) "/" (~action :action)))
                    `(str ~base-url  (~action :service) "/" (~action :subject) "/" (~action :action))))
      :method ~(if (= mode :async-mode)  `:post
                   (if get-allowed `(get-http-method ~action) `:post))
      :thread ~thread
      :rec-id (~action :req_id)
      :headers
      {"Content-Type"  "application/json"
       "Connection" "keep-alive"
       "Authorization" ~(case mode
                          :user-mode  `(session/get-credentials (~action :user_name))
                          :async-mode async-credentials
                          :global-mode global-credentials)}
      :body ~(if (= mode :async-mode)
               `(json/generate-string {:UserName (~action :user_name)
                                       :TicketID (~action :subject)
                                       :Request  (fill-async-request
                                                  ~action)})
               `(~action :parameters))})

(defn url-builder-factory [mode get-allowed {:keys [base-url async-action-url]}]
  (if (= mode :async-mode)
    (fn [_] async-action-url)
    (if get-allowed
      (fn [action] (if (= (action :action) "get")
                     (str base-url  (action :service) "/" (action :subject))
                     (str base-url (action :service) "/" (action :subject) "/" (action :action))))
      (fn [action] (str base-url  (action :service) "/" (action :subject) "/" (action :action))))))

(defn method-builder-factory [mode get-allowed]
  (if (= mode :async-mode)
    (fn [_] :post)
    (if get-allowed
      (fn [action] (if (= (action :action) "get")
                     :get
                     :post))
      (fn [_] :post))))


(defn authorization-builder-factory [mode {:keys [async-credentials global-credentials]}]
  (case mode
    :user-mode  (fn [action] (session/get-credentials (action :user_name)))
    :async-mode (fn [_] async-credentials)
    :global-mode (fn [_] global-credentials)))

(defn body-builder-factory [mode]
  (if (= mode :async-mode)
    (fn [action] (json/generate-string {:UserName (action :user_name)
                                        :TicketID (action :subject)
                                        :Request  (fill-async-request
                                                   action)}))
    (fn [action]
      (action :parameters))))

(defn build-opts-factory [mode get-allowed config workers]
  (let [url-builder (url-builder-factory mode get-allowed config)
        method-builder (method-builder-factory mode get-allowed)
        authorization-builder (authorization-builder-factory mode workers)
        body-builder (body-builder-factory mode)]
    (fn [action thread]
      {:url (url-builder action)
       :method (method-builder action)
       :thread thread
       :rec-id (action :req_id)
       :mode mode
       :headers
       {"Content-Type"  "application/json"
        "Connection" "keep-alive"
        "Authorization"  (#(if (nil? %) (error "Auth is nil for action " action) %)
                          (authorization-builder action))}
       :body (body-builder action)})))



(defn get-pusher-factory 
  "Returns pusher factory. 
   Depends on  async-pusher-enabled created factory generates asyn or sync pusher factory.
   Pusher generated by async factory uses async http request pushing task to sm.
   Pusher factory requers the following parameters to create pusher:
    mode - one of :user-mode, :global-mode or async-mode 
    get-allowed - true or false, make sence for :user-mode only, allow user place get request 
    config -  global configuration, in most cases thes best choise is (config/get-config)
    workers -  workers configuration, in most cases thes best choise is (config/get-workers) 
   "
  [async-pusher-enabled]
  (timbre/with-merged-config
    {:appenders {:spit (appenders/spit-appender {:fname "log/pusher_conf.log"})}}
    (if (true? async-pusher-enabled)
      (fn  [mode get-allowed config workers]
        (let [option-builder (build-opts-factory mode get-allowed config workers)]
          (fn [action thread write-channel-callback]
            (http/request (option-builder action thread) write-channel-callback))))

      (fn  [mode get-allowed config workers]
        (let [option-builder (build-opts-factory mode get-allowed config workers)]
          (fn [action thread]
            @(http/request (option-builder action thread))))))))

#_(defn sync-pusher-factory [mode get-allowed config _]
    (let [{:keys [base-url
                  async-action-url
                  async-credentials
                  global-credentials]} config]
      (case mode
        :user-mode
        (if get-allowed
          (fn [action thread] @(http/request (opts action thread  :user-mode true base-url async-credentials global-credentials async-action-url)))
          (fn [action thread] @(http/request (opts action thread  :user-mode false base-url async-credentials global-credentials async-action-url))))
        :global-mode
        (if get-allowed
          (fn [action thread] @(http/request (opts action thread  :global-mode true base-url async-credentials global-credentials async-action-url)))
          (fn [action thread] @(http/request (opts action thread  :global-mode false base-url async-credentials global-credentials async-action-url))))
        :async-mode (fn [action thread]
                      @(http/request (opts action thread  :async-mode get-allowed base-url async-credentials global-credentials async-action-url))))))

#_(defn async-pusher-factory [mode get-allowed config _]
    (let [{:keys [base-url
                  async-action-url
                  async-credentials
                  global-credentials]} config]
      (case mode
        :user-mode
        (if get-allowed
          (fn [action thread write-channel-callback] (http/request (opts action thread  :user-mode true base-url async-credentials global-credentials async-action-url) write-channel-callback))
          (fn [action thread write-channel-callback] (http/request (opts action thread  :user-mode false base-url async-credentials global-credentials async-action-url) write-channel-callback)))
        :global-mode
        (if get-allowed
          (fn [action thread write-channel-callback] (http/request (opts action thread  :global-mode true base-url async-credentials global-credentials async-action-url) write-channel-callback))
          (fn [action thread write-channel-callback] (http/request (opts action thread  :global-mode false base-url async-credentials global-credentials async-action-url) write-channel-callback)))
        :async-mode (fn [action thread write-channel-callback]
                      (http/request (opts action thread  :async-mode get-allowed base-url async-credentials global-credentials async-action-url) write-channel-callback)))))

(defn processor [resp thread]
  (timbre/with-merged-config
    {:appenders {:spit (appenders/spit-appender {:fname "log/pusher.log"})}}
    (if (nil? resp)
      (do (warnf "Thread %s got empty responce. Time out? " thread)   tr/RETRY-ACTION)
      (let [opts (:opts resp)
            {:keys [user-name  credentials mode rec-id]}  opts
            result-code (result/process resp)]
        (debug thread ":Task:" rec-id "(" (:status resp) (:body resp) ") result code " result-code )
        (_case  result-code
                result/OK  tr/NEXT-ACTION

                result/NOT-ATHORIZED
                (if (= mode :user-mode)
                  (do
                    (session/remove-credentials user-name credentials)
                    tr/NEXT-ACTION)
                  (do
                    (fatal (format "Mode %s user not athorized. Thread %s exited." mode thread))
                    tr/EXIT-THREAD))

                result/TOO-MANY-THREADS tr/RETRY-ACTION

                result/SERVER-NOT-AVAILABLE tr/SERVER-NOT-AVAILABLE

                (do  (fatal "Can't parse result: " resp "\n")
                     tr/EXIT-THREAD))))))


