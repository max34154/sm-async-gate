(ns sm_async_api.dal.configure
  #_{:clj-kondo/ignore [:refer-all]}
  (:require [hikari-cp.core :refer [make-datasource]]
            [clojure.java.jdbc :as jdbc]
            [sm_async_api.validate]
            [clojure.string :as str]
            [sm_async_api.config :as config]
            [sm_async_api.dal.globals :refer [db user-action task-action request-action]]
            [sm_async_api.dal.user :as dal-u]
            [sm_async_api.dal.task :as dal-t]
            [sm_async_api.dal.request :as dal-r]
            [taoensso.timbre :as timbre
             :refer [;log  trace  debug  info  warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf
                     ;spy get-env
                     debug fatal report]]
            #_[taoensso.timbre.appenders.core :as appenders]))


(def ^:private db_tables {"ATTACHMENT" 1  "REQUEST" 10  "RESPONCE" 100 "USER" 1000})

(def ^:private db_correct_value 1111)

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

#_(defn- agent-waiting [agt f limit  message]
  (send agt f)
  (when (some? message) (print message))
  (loop [x 0]
    (Thread/sleep 20)
    (when (some? message) (print "."))
    (when (nil? @agt)
      (if (> x limit)
        (throw (AssertionError. (str "Waiting limit %s exceeded for agent" agt)))
        (recur (inc x)))))
  (when (some? message) (print "\n"))
  (debug "Completed:" @agt))


(defn configure-database []
  (let [db-config (:database @config/config)]
    (debug "Db config " db-config)
    (try
      (when (some? db-config)
        (send db  (fn [_]  {:datasource (make-datasource (open-db db-config))}))
        (when-not (await-for 10000 db) (throw (AssertionError. "Pool configuration error")))
        (check_db db-config)
        (send user-action (fn [_] (dal-u/configure db-config)))
        (send task-action (fn [_] (dal-t/configure db-config)))
        (send request-action (fn [_] (dal-r/configure db-config)))
        (when-not (await-for 10000 user-action task-action request-action) (throw (AssertionError. "DB functions configuration error"))))
      (catch Exception e (ex-message e)
             (fatal "Database configuratopn error " e)
             -1))))