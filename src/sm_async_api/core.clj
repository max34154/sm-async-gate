(ns sm-async-api.core
  (:require
   [taoensso.timbre :as timbre
    :refer [;log  trace      warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf get-env
            info  fatal]]
   [sm_async_api.config :as cfg :refer [configure]]
   [sm_async_api.dal.configure :refer [configure-database stop-database]]
   [sm-async-api.request.request :refer [start-AccessHTTPServer stop-AccesHTTPServer]]
   [sm_async_api.task.sync_dispatcher :refer [start-pushers stop-pushers]]
   [sm_async_api.utils.options :refer [read-options]]
   [sm_async_api.hook.dispatcher :refer [start-messengers
                                         stop-messengers]]
   [clojure.stacktrace :refer [print-stack-trace]])
  (:gen-class))



(defn shutdown []
  (stop-AccesHTTPServer)
  (stop-pushers)
  (stop-messengers)
  (stop-database))




(defn startup
  ([config] {:pre [(some? config)]}
            (try
              (let [port (-> config :-port 
                               (#(if (vector? %) (% 0) %))
                               (#(if (integer? %) % (Integer/parseInt % 10))))]
                (when-not (integer? port)
                  (throw (AssertionError. (str "Incorrect port number: "  (config :-port)))))
                (info "Read configuration from  " (config :-path))
                (when (nil? (configure (config :-path))) (throw (AssertionError. (str "Incorrect or missing configuration in  "  (config :-path)))))
                (info "SM Base URL:" (cfg/get-config :base-url))
                (info "Authorization URL:" (cfg/get-config :auth-url))
                (info "SM Async Actions URL:"(cfg/get-config :async-action-url) )
                (info "Start initialization.")
                (when (= (configure-database config) -1) (throw (AssertionError. "Database configuration error.")))
                (when (some? (start-pushers)) (throw (AssertionError.  "Pushers configuration error.")))
                (when (some? (start-messengers)) (throw (AssertionError. "Messengers configuration error")))
                (when (nil? (start-AccessHTTPServer port)) (throw (AssertionError. "Gatekeeper configuration error"))) 
                (info "Initialization sucessfully completed."))
              (catch Exception e
                (fatal (ex-message e) "\nInitialization failed.")
                (print-stack-trace e)
                (shutdown))))
  #_([port path]
     (info "Read configuration")
     (configure path)
     (info "Start initialization.")
     (try
       (when (some? (configure-database)) "Database configuration error.")
       (when (some? (start-pushers))  "Pushers configuration error.")
       (when (some? (start-messengers)) "Messengers configuration error")
       (start-AccessHTTPServer port)
       (info "Initialization sucessfully competed.")
       (catch Exception e  (fatal (ex-message e) "\nInitialization failed.")))))





(defn -main [& args]
  ;(let [startup-config {:-port "8080" :-path "./"}])
  (startup (read-options args {:-port "8080" :-path "./"})))
  ;(startup (Integer/parseInt (or port "8080") 10) path))

(comment (-main "3000"))