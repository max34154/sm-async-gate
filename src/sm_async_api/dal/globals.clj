(ns sm_async_api.dal.globals 
     (:require [clojure.string :as str]))

(defonce db (agent {}))

(defonce user-action (agent nil))

(defonce task-action (agent nil))

(defonce request-action (agent nil))

(def base-filed-list 
   (str/join "," [ "REQ_ID" 
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
                  "SUBJECT"]))

(def task-field-list
 (str/join "," [ "REQ_ID" 
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
                "SUBJECT"]))

(def full-action-field-list 
  (str/join "," [ task-field-list  
                 "CLOSE_TIME"  
                 "RES_STATUS" 
                 "STRINGDECODE(RESULT) as RESULT"]))

(def _default_retry_interval 300)

(def _default_lock_chunk_size 2)

