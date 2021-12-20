(ns sm_async_api.dal.configure
  #_{:clj-kondo/ignore [:refer-all]}
  (:require [hikari-cp.core :refer [make-datasource]]
            [clojure.java.jdbc :as jdbc]
            [sm_async_api.validate]
            [clojure.string :as str]
            [yaml.core :as yaml]
            [sm_async_api.config :as config]
            [clojure.core.async :as a]
            [sm_async_api.dal.globals :refer [db
                                              user-action
                                              task-action
                                              hook-action
                                              request-action
                                              attachment-action]]
            [sm_async_api.dal.user :as dal-u]
            [sm_async_api.dal.task :as dal-t]
            [sm_async_api.dal.request :as dal-r]
            [sm_async_api.dal.hook :as dal-h]
            [sm_async_api.dal.attachment :as dal-a]
            [clojure.java.io :as io]
            [sm_async_api.utils.macro :as m]
            [clojure.stacktrace :as stacktrace]
            [taoensso.timbre :as timbre
             :refer [;log  trace  debug  info  warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf
                     ;spy get-env
                     debug  debugf info warnf fatal report reportf errorf]]
            #_[taoensso.timbre.appenders.core :as appenders])
  (:import [java.io File]))


(def ^:private db_tables {"ATTACHMENT" 1
                          "REQUEST" 10
                          "RESPONCE" 100
                          "USER" 1000
                          "HOOK" 10000
                          "MESSAGE" 100000
                          "MESSAGE_LOG" 1000000})

(def ^:private db_correct_value 1111111)

(def ^:privat ^Integer min-cleaner-period 10000)

(def ^:privat ^Integer default-clean-delay 600000)

(defmulti ^:private open-db (fn [db-config] (:db-type  db-config)))

(defmethod open-db :default [db-config]
  (throw (IllegalArgumentException.
          (str "open-db: Unsupported database type " (:db-type  db-config) "."))))

(defmulti ^:private sql-get-table-list (fn [db-config] (:db-type  db-config)))

(defmethod sql-get-table-list :default [db-config]
  (throw (IllegalArgumentException.
          (str "sql-get-table-list: Unsupported database type " (:db-type  db-config) "."))))


;; H2 Methods START
(defmethod open-db "h2" [db-config]
  (case (:h2-protocol db-config)

    "tcp" {:adapter "h2"
           :url (str "jdbc:h2:tcp:" (:db-host db-config) "/" (:db-name db-config))
           :user (:db-login db-config)
           :password  (or  (:db-password db-config) "")}

    "mem" {:adapter "h2"
           :url  "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1"
           :user        (:db-login db-config)
           :password     (or  (:db-password db-config) "")}

    "file" {:adapter "h2"
            :url (str "jdbc:h2:file:" (or (:h2-path db-config) (str (System/getProperty "user.dir"))))
            :user (:db-login db-config)
            :password  (or  (:db-password db-config) "")}

    (throw (AssertionError.
            (str "Incorrect or not supported h2 protocol " (:h2-protocol db-config) ".")))))

(defmethod sql-get-table-list "h2" [{:keys [db-schema]}]
  (str  "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '"
        db-schema "' AND TABLE_TYPE='TABLE'"))

;; H2 Methods END
;; Postgresql Methods START

(defmethod open-db "postgres" [db-config]
  (let [[host port] (str/split (:db-host db-config) #":")]
    {:auto-commit        true
     :read-only          false
     :connection-timeout 30000
     :validation-timeout 5000
     :idle-timeout       600000
     :max-lifetime       1800000
     :minimum-idle       10
     :maximum-pool-size  10
     :pool-name          "db-pool"
     :adapter            "postgresql"
     :username           (:db-login db-config)
     :password           (:db-password db-config)
     :database-name      (:db-name db-config)
     :server-name        host
     :port-number        port
     ;:stringtype         "unspecified"
     :register-mbeans    false}))

(defmethod sql-get-table-list "postgres" [{:keys [db-schema]}]
  (str  "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '"
        db-schema "' AND TABLE_TYPE='TABLE'"))

;; Postgresql Methods END

(def ^:private db-setup-sql-h2 (slurp "src/sm_async_api/db_setup_h2.sql"))

(def ^:private db-setup-sql-pg (slurp "src/sm_async_api/db_setup_pg.sql"))

(defn- db-setup [{:keys [db-type db-schema]}]
  (str/replace
   (case db-type
     "h2" db-setup-sql-h2
     "postgres" db-setup-sql-pg
     (throw (AssertionError. (str "Incorrect or not supported dbtype  " db-type ".")))) "%SCHEMA%" db-schema))

(defn- correct_db? [db-config]
  (= db_correct_value
     (reduce #(+ ^int %1 ^int (or  (db_tables (%2 :table_name)) 0)) 0
             (jdbc/query @db (sql-get-table-list db-config)))))



(defn- execute-statment-error-fabric [error-processing]
  (fn [error-count  statment]
    (try
      (jdbc/execute! @db statment)
      error-count
      (catch Exception e
        (case error-processing
          :ignore
          (do
            (debugf "Script execution error: statement %s error %s" statment (ex-message e))
            (inc error-count))
          :warn
          (do (warnf "Script execution error: statement %s error %s" statment (ex-message e))
              (inc error-count))
          :exit (do
                  (errorf "Script execution error: statement %s error %s" statment (ex-message e))
                  (reduced 1)))))))

(defn execute-script
  ([script]
   (doseq [l (str/split script #";")]
     (jdbc/execute! @db l)))
  ([script error-handling]
   (reduce (execute-statment-error-fabric error-handling)
           0
           (str/split script #";"))))

(defn- check_db [db-config]
  (debug "Is db correct? " (correct_db? db-config))
  (when-not (correct_db? db-config)
    (report "DB reconfiguration required.")
    (execute-script (db-setup db-config) :warn))
  (report "DB configured."))

(def database-startup-commands {:-db-clean (fn  [{:keys [db-schema]},_]
                                             (info "DB Clean - Started")
                                             (execute-script (str "TRUNCATE TABLE " db-schema ".ATTACHMENT;"
                                                                  "TRUNCATE TABLE " db-schema ".RESPONCE;"
                                                                  "DELETE FROM " db-schema ".REQUEST;"
                                                                  "TRUNCATE TABLE " db-schema ".HOOK;"
                                                                  "TRUNCATE TABLE " db-schema ".MESSAGE;"
                                                                  "TRUNCATE TABLE "  db-schema ".MESSAGE_LOG;"))
                                             (info "DB Clean - Finished"))


                                :-db-remove-requests (fn  [{:keys [db-schema]}, _]
                                                       (info "DB Remove Request - Started")
                                                       (execute-script (str "TRUNCATE TABLE " db-schema ".ATTACHMENT;"
                                                                            "TRUNCATE TABLE " db-schema ".RESPONCE;"
                                                                            "DELETE FROM " db-schema ".REQUEST;"))
                                                       (info "DB Remove Request - Finished"))

                                :-db-remove-messages (fn  [{:keys [db-schema]}, _]
                                                       (info "DB Remove Messages - Started")
                                                       (execute-script (str "TRUNCATE TABLE " db-schema " .MESSAGE;"))
                                                       (info "DB Remove Messages - Finished"))

                                :-db-remove-logs (fn  [{:keys [db-schema]}, _]
                                                   (info "DB Remove Logs - Started")
                                                   (execute-script (str "TRUNCATE TABLE " db-schema " .MESSAGE_LOG;"))
                                                   (info "DB Remove Logs - Finished"))

                                :-db-squeeze (fn  [_ db-clean-delay]
                                               (info "DB Squeeze - Started")
                                               (try  (->  (get db-clean-delay 0)
                                                          #_{:clj-kondo/ignore [:invalid-arity]}
                                                          (m/if-do vector? (get 0))
                                                          (#(if (nil? %) 0 %))
                                                          (m/if-do string? (Integer/parseInt 10))
                                                          ((request-action :cleanup)))
                                                     (info "DB Squeeze - finished")
                                                     (catch NumberFormatException _ (warnf "Incorrect -squeeze delay value %s. Squeeze skiped" db-clean-delay))))})


(defn reload-hook-templates
  ([db-config path] (reload-hook-templates db-config path false))
  ([db-config path force-reload?]
   (when (or (true? force-reload?)
             (and (= (:db-type db-config) "h2")
                  (= (:h2-protocol db-config) "mem")))
     (try
       (let [add-hook (:update-or-insert-template @hook-action)]
         (reportf "%d hooks loaded" (reduce (fn [count hook]
                                              (add-hook hook) (inc count)) 0 (yaml/from-file (str path "hook.yml")))))
       (catch Exception e (ex-message e)
              (errorf "Hooks are not loaded. Error: %s" e))))))

(defn unload-hook-template [path]
   ;(.exists (io/file (str path "hook.bkp"))
  (let [backup-file  (str path "hook.bkp")
        work-file (str path "hook.yml")]
    (io/delete-file backup-file true)
    (.renameTo (File. work-file) (File. backup-file))
    (with-open [work-file (io/writer work-file)]
      (.write work-file (yaml/generate-string
                         ((@hook-action :get-all-templates)))))))


(defn cleaner-start []
  (reportf "Cleaner started. Cleaning period is %s, delays is %s"
           (-> @config/config :database :db-clean-period)
           (-> @config/config :database :db-clean-delay))
  (a/thread
    (let [{:keys [db-clean-period db-clean-delay]} (:database @config/config)
          db-clean-delay (if (pos-int? db-clean-delay) db-clean-delay default-clean-delay)]
      (loop [x db-clean-period]
        (when (pos-int? x)
          (if (< x min-cleaner-period)
            (Thread/sleep min-cleaner-period)
            (Thread/sleep x))
          ((request-action :cleanup) db-clean-delay)
          (recur (->  @config/config :database :db-clean-period))))
      (report "Cleaner exited."))))

(defn cleaner-reconfigure-period
  [^Integer new-period]
  (swap! @config/config update-in [:database :db-clean-period] (fn [_]  new-period)))

(defn cleaner-reconfigure-delay
  [^Integer new-delay] {:pre [(pos-int? new-delay)]}
  (swap! @config/config update-in [:database :db-clean-delay] (fn [_]  new-delay)))

(defn cleaner-stop [] (cleaner-reconfigure-period 0))


(defn- run-option-fabric [db-config]
  (fn [ret-val option]
    (when (= ret-val -1) (reduced -1))
    (when-let [option-func (database-startup-commands (key option))]
      (option-func db-config (val option)))))

(defn configure-database
  ([]
   (let [db-config (:database @config/config)
         path (:path  @config/config)]
     (debug "Db config " db-config)
     (try
       (when (some? db-config)
         (send db  (fn [_]  {:datasource (make-datasource (open-db db-config))}))
         (when-not (await-for 10000 db) (throw (AssertionError. "Pool configuration error")))
         (check_db db-config)
         (send user-action (fn [_] (dal-u/configure db-config)))
         (send task-action (fn [_] (dal-t/configure db-config)))
         (send request-action (fn [_] (dal-r/configure db-config)))
         (send hook-action (fn [_] (dal-h/configure db-config)))
         (send attachment-action (fn [_] (dal-a/configure db-config)))
         (when-not (await-for 10000 user-action task-action request-action hook-action attachment-action)
           (throw (AssertionError. (str "DB functions configuration error "
                                        "user action " @user-action
                                        "task-action " @task-action
                                        "request-action " @request-action
                                        "hook-action " @hook-action))))
         (when (some? (:log-message-delivery @hook-action)) (info "Web-hook message logging enabled."))
         (when (pos-int? (db-config :db-clean-period))
           (cleaner-start)))
       ((@task-action :clear-locks))
       (reload-hook-templates db-config path)
       (catch Exception e (ex-message e)
              (fatal "Database configuration error " e)
              (println "!!!!!Stack trace:")
              (clojure.stacktrace/print-stack-trace e)
              -1))))
  ([options]
   (reduce (run-option-fabric (:database @config/config)) (configure-database) options)))



(defn stop-database []
  (let [db-config (:database @config/config)
        path (:path  @config/config)]
    (when (and (= (:db-type db-config) "h2")
               (= (:h2-protocol db-config) "mem"))
      (try
        (reportf "Hooks unload required.")
        (unload-hook-template path)
        (reportf "Hooks unloaded.")
        (catch Exception e (ex-message e)
               (errorf "Hooks are not unloaded. Error: %s" e))))))
