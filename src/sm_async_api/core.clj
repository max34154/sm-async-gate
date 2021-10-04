(ns sm-async-api.core
  (:require
   [taoensso.timbre :as timbre
    :refer [;log  trace      warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf get-env
            info  fatal]]
   [sm_async_api.config :refer [configure]]
   [sm_async_api.dal.configure :refer [configure-database stop-database]]
   [sm-async-api.request.request :refer [start-AccessHTTPServer stop-AccesHTTPServer]]
   [sm_async_api.task.sync_dispatcher :refer [start-pushers stop-pushers]])
  (:gen-class))



(defn shutdown []
  (stop-AccesHTTPServer)
  (stop-pushers)
  (stop-database))

(defn startup [port path]
  (info "Read configuration")
  (configure path)
  (info "Configure database")
  (try
    (when (some? (configure-database)) "Database configuration error.")
    (when (some? (start-pushers))  "Pushers configuration error.")
    (start-AccessHTTPServer port)
    (info "Initialization sucessfully competed.")
    (catch Exception e  (fatal (ex-message e) "\nInitialization failed."))))

(defn -main [& [port path]]
  (startup (Integer/parseInt (or port "8080") 10) path))



(comment (-main "3000"))