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
                                              request-action]]
            [sm_async_api.dal.user :as dal-u]
            [sm_async_api.dal.task :as dal-t]
            [sm_async_api.dal.request :as dal-r]
            [sm_async_api.dal.hook :as dal-h]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre
             :refer [;log  trace  debug  info  warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf
                     ;spy get-env
                     debug fatal report reportf errorf]]
            #_[taoensso.timbre.appenders.core :as appenders])
  (:import [java.io File]))


(def ^:private db_tables {"ATTACHMENT" 1
                          "REQUEST" 10
                          "RESPONCE" 100
                          "USER" 1000
                          "HOOK" 10000
                          "MESSAGE" 100000})

(def ^:private db_correct_value 111111)

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
#_(defmethod open-db "h2" [db-config]
    (case (:h2-protocol db-config)

      "tcp" {:classname   "org.h2.Driver"
             :subprotocol "h2"
             :subname (str  "tcp://" (:db-host db-config)  "/" (:db-name db-config))
             :user (:db-login db-config)
             :password  (or  (:db-password db-config) "")}

      "mem"  {:classname   "org.h2.Driver"
              :subprotocol "h2:mem"
              :subname     "demo;DB_CLOSE_DELAY=-1"
              :user        (:db-login db-config)
              :password     (or  (:db-password db-config) "")}

      "file" {:classname   "org.h2.Driver"
              :subprotocol "h2:file"
              :subname (or (:h2-path db-config) (str (System/getProperty "user.dir")))
              :user (:db-login db-config)
              :password  (or  (:db-password db-config) "")}

      (throw (AssertionError. (str "Incorrect or not supported h2 protocol " (:h2-protocol db-config) ".")))))

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

(def ^:private db-setup-sql-h2 (slurp "src/sm_async_api/db_setup_h2.sql"))

(defn- db-setup [{:keys [db-type db-schema]}]
  (str/replace
   (case db-type
     "h2" db-setup-sql-h2
     (throw (AssertionError. (str "Incorrect or not supported dbtype  " db-type ".")))) "%SCHEMA%" db-schema))

(defn- correct_db? [db-config]
  (= db_correct_value
     (reduce #(+ ^int %1 ^int (or  (db_tables (%2 :table_name)) 0)) 0
             (jdbc/query @db (sql-get-table-list db-config)))))


(defn execute-script [script]
  (doseq [l (str/split script #";")]
    (jdbc/execute! @db l)))

(defn- check_db [db-config]
  (debug "Is db correct? " (correct_db? db-config))
  (when-not (correct_db? db-config)
    (report "DB reconfiguration required.")
    (execute-script (db-setup db-config)))
  (report "DB configured."))

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

(defn configure-database []
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
        (when-not (await-for 10000 user-action task-action request-action hook-action)
          (throw (AssertionError. (str "DB functions configuration error "
                                       "user action " @user-action
                                       "task-action " @task-action
                                       "request-action " @request-action
                                       "hook-action " @hook-action))))
        (when (pos-int? (db-config :db-clean-period))
          (cleaner-start)))
      ((@task-action :clear-locks))
      (reload-hook-templates db-config path)
      (catch Exception e (ex-message e)
             (fatal "Database configuration error " e)
             (println "!!!!!Stack trace:")
             (clojure.stacktrace/print-stack-trace e)
             -1))))

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