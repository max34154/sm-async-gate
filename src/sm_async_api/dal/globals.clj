(ns sm_async_api.dal.globals
  (:require [clojure.string :as str]))

(defonce db (agent {}))

(defonce user-action (agent nil))

(defonce task-action (agent nil))

(defonce request-action (agent nil))

(defonce hook-action (agent nil))

(defonce attachment-action (agent nil))

(defonce cleaner (agent nil))

(def base-filed-list
  (str/join "," ["REQ_ID"
                 "USER_NAME"
                 "STATUS"
                 "SCHEDULE_NAME"
                 "EXECUTION_MODE"
                 "EXECUTION_RETRIES"
                 "RETRY_INTERVAL"
                 "ACTION"
                 "PARAMETERS"
                 "EXPIRE_AT"
                 "SERVICE"
                 "SUBJECT"
                 "TAG"]))

;; 
;; Database type dependend. Please add methods for each supported db 
;; 

(defmulti task-field-list (fn [db-config] (:db-type  db-config)))


(defmethod task-field-list :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))

(defmulti  full-action-field-list (fn [db-config] (:db-type  db-config)))

(defmethod full-action-field-list :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))


;; H2 methods 
(defmethod task-field-list "h2" [_]
  (str/join "," ["REQ_ID"
                 "USER_NAME"
                 "EXECUTION_MODE"
                 "EXECUTION_RETRIES"
                 "RETRY_INTERVAL"
                 "ACTION"
                 "STRINGDECODE(PARAMETERS) as PARAMETERS"
                 "ATTEMPT"
                 "NEXT_RUN"
                 "EXPIRE_AT"
                 "SERVICE"
                 "SUBJECT"
                 "TAG"]))

(defmethod full-action-field-list "h2" [dbconfig]
  (str (task-field-list dbconfig) ","
       (str/join "," ["CLOSE_TIME"
                      "RES_STATUS"
                      "STRINGDECODE(RESULT) as RESULT"])))
;; H2 methods END 

;; Postgres methods 
(defmethod task-field-list "postgres" [_]
  (str/join "," ["REQ_ID"
                 "USER_NAME"
                 "EXECUTION_MODE"
                 "EXECUTION_RETRIES"
                 "RETRY_INTERVAL"
                 "ACTION"
                 "PARAMETERS"
                 "ATTEMPT"
                 "NEXT_RUN"
                 "EXPIRE_AT"
                 "SERVICE"
                 "SUBJECT"
                 "TAG"]))

(defmethod full-action-field-list "postgres" [dbconfig]
  (str (task-field-list dbconfig) ","
       (str/join "," ["CLOSE_TIME"
                      "RES_STATUS"
                      "RESULT"])))
;; Postgres methods END 

(def _default_retry_interval 300)

(def _default_lock_chunk_size 2)

(def default-sm-user-name "sm")

