#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.dal
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [sm_async_api.validate]
            [clojure.string :as str]
            [sm_async_api.config :as config]
            [clojure.java.io :as io]
            [sm_async_api.utils.crypto :as crypto :refer [encrypt decrypt]]
            [sm_async_api.utils.macro :refer [unixtime->timestamp tod]]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]
            ;[taoensso.timbre.appenders.core :as appenders]
            )
  (:import [java.sql SQLException]))

;(timbre/merge-config!  {:appenders {:spit (appenders/spit-appender {:fname "log/dal.log"})}})



(let [db-protocol "tcp"            ; "file|mem|tcp"
      db-host     "localhost:9092" ; "path|host:port"
      db-name     "~/test"]

  (def db-tcp  {:classname   "org.h2.Driver" ; must be in classpath
                :subprotocol "h2"
                :subname (str db-protocol "://" db-host "/" db-name)
                 ; Any additional keys are passed to the driver
                 ; as driver-specific properties.
                :user     "sa"
                :password ""}))

(defonce db (atom db-tcp))

(defn open_db [& [_db]]
  (if (nil? _db) (reset! db  db-tcp) (reset! db  _db))
  (debug "Database parameters are " @db))


(def db_local
  {:classname   "org.h2.Driver"
   :subprotocol "h2:file"
   :subname     (str (System/getProperty "user.dir"))
   :user        "sa"
   :password    ""})

(def db_mem
  {:classname   "org.h2.Driver"
   :subprotocol "h2:mem"
   :subname     "demo;DB_CLOSE_DELAY=-1"
   :user        "sa"
   :password    ""})
;;
;; Here is an example of creating a symbol in the 
;; existing namespace as an alias to a namespace
;;
;(require '[clojure.java.jdbc :as sql]) 
;(sql/with-connection db
;  (sql/with-query-results rs ["select * from customer"]
;    (dorun (map #(println (:lastname %)) rs))))


(def db_oracle {:classname "oracle.jdbc.OracleDriver"  ; must be in classpath
                :subprotocol "oracle"
                :subname "thin:@172.27.1.7:1521:SID"  ; If that does not work try:   thin:@172.27.1.7:1521/SID
                :user "user"
                :password "pwd"})

(def requests " ASYNC.REQUEST ")

(def results " ASYNC.RESPONCE ")

(def attachments " ASYNC.ATTACHMENT ")

(def base-filed-list "REQ_ID, USER_NAME, STATUS, SCHEDULE_NAME, EXECUTION_MODE, EXECUTION_RETRIES, RETRY_INTERVAL, ACTION, PARAMETERS,EXPIRE_AT,SERVICE, SUBJECT")

(def task_field_list "REQ_ID, USER_NAME, EXECUTION_MODE, EXECUTION_RETRIES, RETRY_INTERVAL, ACTION, STRINGDECODE(PARAMETERS) as PARAMETERS, ATTEMPT, NEXT_RUN, EXPIRE_AT,SERVICE, SUBJECT ")

(def full-action-field-list (str task_field_list ", CLOSE_TIME, RES_STATUS, STRINGDECODE(RESULT) as RESULT"))

(def  sql-insert  (str "INSERT INTO " requests " ("  base-filed-list ") VALUES(%s);"))

(def _default_retry_interval 300)

(def _default_lock_chunk_size 2)

(def db_config (slurp "src/sm_async_api/db_setup.sql"))
;(require '[clojure.java.jdbc :as jdbc])
;(jdbc/query   db ["SELECT * FROM INFORMATION_SCHEMA.EXTERNAL_ACTONS"])

(def db_tables {"ATTACHMENT" 1  "REQUEST" 10  "RESPONCE" 100})

(def db_correct_value 111)

(def sql_get_table_list "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'ASYNC' AND TABLE_TYPE='TABLE'")

(defn correct_db? []
  (= db_correct_value
     (reduce #(+ ^int %1 ^int (or  (db_tables (%2 :table_name)) 0)) 0
             (jdbc/query @db sql_get_table_list))))

(defn check_db_old []
  (let [table_list (jdbc/query @db sql_get_table_list)]
    (info "...found tables:" (reduce str table_list))
    (if (= db_correct_value
           (reduce #(+ ^int %1 ^int (or  (db_tables (%2 :table_name)) 0)) 0 table_list))
      (info "DB already configured")
      (do (info "DB reconfiguration required.")
          (doseq [l (str/split db_config #";")]
            (jdbc/execute! @db l))
          (info "DB reconfigured.")))))

(defn execute_script [script]
  (doseq [l (str/split script #";")]
    ;;(debug "Execute: " l)
    (jdbc/execute! @db l)))

(defn check_db []
  (debug "Table list ")
  (doseq [l  (jdbc/query @db sql_get_table_list)] (debug "..." l))
  (debug  "Magic number" db_correct_value " = "  (reduce #(+ ^int %1 ^int (or  (db_tables (%2 :table_name)) 0)) 0
                                                         (jdbc/query @db sql_get_table_list)))
  (debug "Is db correct? " (correct_db?))
  (if (correct_db?)
    (info "DB already configured")
    (do (info "DB reconfiguration required.")
        (doseq [l (str/split db_config #";")]
          (jdbc/execute! @db l))
        (info "DB reconfigured."))))

(defmacro _qoute [_str] `(str "'" ~_str "'"))

(defn- _insert_action [req] {:pre [(s/valid? :sm_async_api.validate/post-action-request req)]}
  (let [{:keys [rec_id route-params user_name  body service]} req
        {:keys [status schedule_name execution_mode execution_retries retry_interval parameters expire_at]} body]
    (jdbc/execute! @db
                   (format sql-insert
                           (reduce #(str %1 "," (if (nil? %2) "" %2))
                                   (vector (_qoute rec_id)
                                           (_qoute user_name)
                                           (or (_qoute status) "N")
                                           (_qoute schedule_name)
                                           (_qoute execution_mode)
                                           (if (number? execution_retries) execution_retries 1)
                                           (if (number? retry_interval)  retry_interval _default_retry_interval)
                                           (_qoute (route-params :action_id))
                                           (_qoute parameters)
                                           (if (nil? expire_at) "NULL" expire_at)
                                           (_qoute service)
                                           (_qoute (route-params :name))))))))
; end user actions 


(defn wrap-insert [action req]
  (try (action req)
       (catch SQLException e (error (ex-message e))
              {:err "Can't insert action into DB."})
       (catch  AssertionError e (error (ex-message e))
               {:err "Incorrect action options" :status 422})))

(defn insert_action [req]
  (wrap-insert _insert_action req))


;(let [i (atom 0)]
; (defn generate-unique-id []
;    (format "ATT-%X-%X-%s" (quot (System/currentTimeMillis) 1000) (swap! i inc) (config/get-module-name))))

;(defn set-rec_id [req]
;  (assoc req :rec_id (generate-unique-id)))
(defn- file->bytes [file]
  (with-open [xin (io/input-stream file)
              xout (java.io.ByteArrayOutputStream.)]
    (io/copy xin xout)
    (.toByteArray xout)))


; attachments manipilations 
(defn- _insert_attachment!
  [att_id rec_id name content-type body size]
  {:pre [(s/valid? :sm_async_api.validate/mime-type content-type)
         (s/valid? :sm_async_api.validate/attachment-size size)]}
  (jdbc/insert! @db :async.attachment
                {:att_id att_id
                 :att_req_id rec_id
                 :name  name
                 :content_type content-type
                 :body (file->bytes body)
                 :size size}))
; Multipart format example
;{"file1" 
;  {:filename "MP900216006.JPG", :content-type "image/jpeg", :tempfile #object[java.io.File 0x3fb4b534 "/var/folders/s9/g7ym7x6x48b3lw8yx4cjbjgm0000gn/T/ring-multipart-17930443719747499485.tmp"], :size 26160}, 
; "file2" 
;   {:filename "ДЗМ.png", :content-type "image/png", :tempfile #object[java.io.File 0x1904fb09 "/var/folders/s9/g7ym7x6x48b3lw8yx4cjbjgm0000gn/T/ring-multipart-7763576915672791928.tmp"], :size 5941}}
; !! Он внутри не map массив !! 

(defn insert_multypart_attachment [req]
  (let [mp  (req :multipart-params)
        req_id  (-> req :route-params :action_id)]
    (for [file mp]
      (let [{:keys [filename content-type tempfile size]} (file 1)
            att_id (config/get-uid)]
        (try
          (_insert_attachment! att_id req_id filename content-type tempfile size)
          {:name (file 0)
           :filename filename
           :href att_id}
          (catch AssertionError e  (error (ex-message e))
                 {:name (file 0)
                  :filename filename
                  :err e}))))))

(defn insert_attachment  [req]
  (let [{:keys [route-params  content-type content-length headers body]}  req
        att_id (config/get-uid)
        req_id  (:action_id route-params)
        filename   ((str/split
                     ((str/split (headers "content-disposition") #";" 3) 1)  ; cut out filename=xx content despostion string 
                     #"=" 2) 1)] ; cut out xx from filename=xx 
    (_insert_attachment! att_id req_id filename content-type body   content-length)
    {:href att_id}))

(defn get_attachments_list [req]
  (jdbc/query @db (str
                   "SELECT ATT_ID as href, NAME, CONTENT_TYPE, SIZE FROM" attachments
                   "WHERE ATT_REQ_ID='" (-> req :route-params :action_id) "'")))

(defn blob-to-byte [blob]
  (let [ary (byte-array (.length blob))
        is (.getBinaryStream blob)]
    (.read is ary)
    (.close is)
    ary))

(defn get_attachment [req]
  (jdbc/query @db
              [(str
                "SELECT NAME, CONTENT_TYPE, SIZE, BODY FROM" attachments
                "WHERE ATT_ID='" (-> req :route-params :attachment_id) "' "
                "AND ATT_REQ_ID='" (-> req :route-params :action_id) "'")]
                         ;   {:row-fn #(assoc % :body (->> % :body blob-to-byte))}))
              {:row-fn #(assoc % :body (->> % :body blob-to-byte io/input-stream))}))


#_(defn getx []
    (first (get_attachment {:route-params {:attachment_id "ATT-60FFF063-1-1AEF" :action_id "60F957F5-6-1AEF"}})))





#_(defn insert_action_old [req]
    (try (_insert_action req)
         (catch SQLException e (error (ex-message e))
                {:err "Can't insert action into DB."})
         (catch  AssertionError e (error (ex-message e))
                 {:err "Incorrect action options" :status 422})))

; Actions manipulations 

(defn cancel_action [req]
  (jdbc/execute! @db (format
                      (str "UPDATE " requests " set status='C' WHERE REQ_ID='%s' and user_name='%s'")
                      (-> req :route-params :action_id) (req :user_name))))

(defn run_action [req]
  (jdbc/execute! @db (format
                      (str "UPDATE " requests " set status='N' WHERE  status='W' REQ_ID='%s' and user_name='%s'")
                      (-> req :route-params :action_id) (req :user_name))))

(defn get_result  [req]
  (jdbc/query @db
              (str "SELECT RES_REQ_ID, STRINGDECODE(result) as result, close_time FROM " results "WHERE RES_REQ_ID='" (-> req :route-params :action_id) "'")))

(defn get_action  [req]
  (jdbc/query @db
              (str "SELECT " full-action-field-list " FROM " requests
                   "LEFT JOIN " results " ON REQ_ID=RES_REQ_ID "
                   "WHERE REQ_ID='" (-> req :route-params :action_id) "'")))


;--- worker initiated actions 


(def sql_lock_tasks  (str "UPDATE " requests
                          " set status='L',locked_by='%s',lock_time=SYSDATE"
                          " where  (status='N' or status = '') and next_run < SYSDATE and %s limit %s"))



(defn lock_tasks
  " Lock tasks for external worker locker, whoes use API to lock tasks
     If schedule-name specified - select tasks from this shedule only, otherwise any tasks without schedule.
     If chunk_size specified - maximum number of selected task is chunk_size, otherwise - _default_lock_chunk_size
     Return number of locked tasks 
     "
  [^String locker  ^String schedule-name ^Integer chunk_size]
  (jdbc/execute!   @db
                   (format sql_lock_tasks locker
                           (if (nil? schedule-name)
                             "(schedule_name is null or schedule_name = '')"
                             (format "schedule_name='%s'" schedule-name))
                           (or chunk_size _default_lock_chunk_size))))


(defn user-lock-conditions
  "All the tasks create by particular user  exclude tasks in ScheduleOnly mode"
  [^String user-name]
  (str " (execution_mode='IS' or execution_mode='I') and  user_name='"
       user-name "' "))

(defn user-list-conditions
  "All the tasks create by particular user  exclude tasks in ScheduleOnly mode"
  [^String included-names]
  (str " (execution_mode='IS' or execution_mode='I') and  user_name  IN ('"
       included-names "') "))

(defn global-lock-condition
  "All tasks from all users except excluded"
  [^String excluded-names]
  (str " (execution_mode='IS' or execution_mode='I') and  user_name NOT IN ('"
       excluded-names "') "))

(def global-lock-condition-global-only
  "execution_mode='IS' or execution_mode='I'")

(def async-lock-condition
  "All async tasks"
  " execution_mode='S' ")




(defn named-pusher-lock-tasks
  "Lock task for pusher dedicated to user user-name
   Only tasks in I(mmediate) or IS(Immediate and Schedule) modes 
   posted by user user-name are eligable for lock
   Return number of locked tasks"
  [^String pisher-id ^Integer chunk_size ^String user-name]
  (jdbc/execute!   @db
                   (format sql_lock_tasks pisher-id
                           (str " (execution_mode='IS' or execution_mode='I') and  user_name='"
                                user-name "' ")
                           chunk_size)))
(defn async-pusher-lock-tasks
  "Lock task for pusher  dedicated to Shedule mode"
  [^String pisher-id ^Integer chunk_size ^String _]
  (jdbc/execute!   @db
                   (format sql_lock_tasks pisher-id
                           " execution_mode='S' "
                           chunk_size)))

(defn default-pusher-lock-tasks
  "Lock task for default pusher
   Only tasks in I(mmediate) or IS(Immediate and Schedule) modes 
   posted by any user who has not dedicated pusher are eligable for lock
   Return number of locked tasks"
  [^String pisher-id ^Integer chunk_size ^String excluded-names]
  (jdbc/execute!   @db
                   (format sql_lock_tasks pisher-id
                           (str " (execution_mode='IS' or execution_mode='I') and  user_name NOT IN ("
                                excluded-names ")) ")
                           chunk_size)))

;
; By default any result record means that task process are finished.
; Intermidiate result should have finished filed with 'f'
;
(def sql_get_tasks (str "SELECT " task_field_list " FROM " requests
                        " LEFT JOIN " results " ON REQ_ID=RES_REQ_ID "
                        " WHERE  (RES_REQ_ID is NULL or finished='f') and status='L' and locked_by='%s'"))

(defn get_tasks
  "Select tasks locked for worker"
  [^String locker]
  (jdbc/query   @db
                (format sql_get_tasks locker)))

(def sql-lock-tasks-on-time  (str "UPDATE " requests
                                  " set status='L',lock_time='%s',locked_by='%s'"
                                  " where  (status='N' or status = '') and next_run < SYSDATE and %s limit %s"))

(def sql-get-tasks-on-time (str "SELECT " task_field_list " FROM " requests
                                " WHERE  lock_time='%s' and status='L' and locked_by='%s'"))

(defn task-reader
  "Lock task for pusher  according to specified condition.
   According to prefetch strategy returns only just-locked tasks.
   Use get_task if you need all tasks locked by locker."
  [^String pusher-id ^Integer chunk_size ^String condition]
  ;(debug "Task requested for " pusher-id " with condition " condition " chank_size" chunk_size)
  (jdbc/with-db-transaction [t-con @db]
    (let [lock-time (unixtime->timestamp (tod))]
     ; (debug "lock-time" lock-time)
      (when (< 0  ((jdbc/execute!   t-con
                                    (format sql-lock-tasks-on-time
                                            lock-time
                                            pusher-id
                                            condition
                                            chunk_size)) 0))
        (jdbc/query   t-con
                      (format sql-get-tasks-on-time lock-time pusher-id))))))

(def sql_reschedule_task
  (str "UPDATE " requests
       " set status = case attempt >= execution_retries when true then'E'else'N'end,"
       "next_run = DATEADD(SECOND,retry_interval,next_run), "
       "attempt=attempt+1 "
       "WHERE status='L' and req_id=?"))

(defn reschedule_task [^String id]
  (jdbc/execute! @db  [sql_reschedule_task id]))

(def sql_unlock_task
  (str "UPDATE " requests
       " set status='N' "
       " WHERE status='L' and req_id='%s'"))

(defn unlock_task
  "Just remove task lock"
  [^String id]
  (jdbc/execute!  @db (format  sql_unlock_task  id)))

(defn clear_locks []
  (jdbc/execute!  @db
                  (str "UPDATE " requests " set status='N' "
                       "WHERE req_id in "
                       "(SELECT req_id FROM " requests
                       " LEFT JOIN " results " ON REQ_ID=RES_REQ_ID "
                       " WHERE  STATUS='L' AND (RES_REQ_ID is NULL or finished='f'))")))

(defn add-task-result
  "Insert task result generated by pusher"
  [rec-id body status]
  (jdbc/insert! @db results {:res_req_id rec-id
                             :result body
                             :res_status status
                             :finished "t"
                             :close_time (unixtime->timestamp (tod))}))

(defn update-task-result
  [rec-id body status finished]
  (let [row {:res_req_id rec-id
             :result body
             :res_status status
             :finished finished
             :close_time (unixtime->timestamp (tod))}]
    (jdbc/with-db-transaction [t-con @db]
      (let [r (jdbc/update! t-con results row ["RES_REQ_ID=?" rec-id])]
        (if (zero? (first r))
          (jdbc/insert! t-con results row)
          r)))))

(defn update-task-result-and-reschedule
  [rec-id body status]
  (let [row {:res_req_id rec-id
             :result body
             :res_status status
             :finished "f"
             :close_time (unixtime->timestamp (tod))}]
    (jdbc/with-db-transaction [t-con @db]
      (let [r (jdbc/update! t-con results row ["RES_REQ_ID=?" rec-id])]
        (when (zero? (first r))
          (jdbc/insert! t-con results row))
        (jdbc/execute! @db  [sql_reschedule_task rec-id])))))



(defn post_task_result
  "Write task result posted by SM"
  ([req] (let [{:keys [route-params body]} req]  (post_task_result (:action_id route-params) (slurp body))))
  ([^String id  result]
   (jdbc/execute! @db (spy
                       (format
                        (str "INSERT INTO  "
                             results "(RES_REQ_ID, result, close_time)"
                             "VALUES( '%s','%s', SYSDATE)")
                        id result)))))

(def sql_cleanup_exited_worker
  (str "MERGE INTO " requests " AS T "
       " USING ( SELECT REQ_ID, CASEWHEN(RES_REQ_ID IS NULL, 'N' , 'R') AS STATUS  FROM " requests
       " LEFT JOIN " results
       " ON REQ_ID=RES_REQ_ID WHERE STATUS='L' AND LOCKED_BY='%s' ) AS S ON T.REQ_ID=S.REQ_ID "
       "WHEN MATCHED THEN UPDATE SET T.STATUS=S.STATUS;"))

(defn cleanup_exited_worker
  "Unlock all no finished tasks for worker.
   Return number of unlocked tasks"
  [worker]
  (jdbc/execute! @db (format  sql_cleanup_exited_worker worker)))


(def sql_posted_results (str "SELECT REQ_ID FROM " results
                             " INNER JOIN " requests " ON REQ_ID=RES_REQ_ID "
                             " WHERE locked_by='%s'"))

(defn get_worker_results
  "Select results posted by worker. For test only"
  [worker]
  (jdbc/query @db (format  sql_posted_results worker)))


; User operations 
(def user-cache " ASYNC.user")
;user cache record 
; {:name :password :expire_at}
; 


(defn encrypt-row [{:keys [password name expire_at]}]
  (let [{:keys [data iv]} (encrypt password name)]
    {:name name :password data :toc iv :expire_at expire_at}))




(defn decrypt-row [{:keys [name password toc expire_at]}]
  {:name name :val {:name name
                    :password (decrypt {:data password :iv toc} name)
                    :expire_at expire_at}})

(defn update-user [row]
  (let [row (->> row
                 encrypt-row
                 (#(when-let [exp  (:expire_at %)]
                     (assoc % :expire_at
                            (unixtime->timestamp (* 1000 exp))))))]
    (jdbc/with-db-transaction [t-con @db]
      (let [result (jdbc/update! t-con user-cache row ["name=?" (row :name)])]
        (if (zero? (first result))
          (jdbc/insert! t-con user-cache row)
          result)))))

(comment (update-user {:name "test" :password "password"}))
(comment (update-user {:name "test3" :password "password"}))
(defn get-users []
  (reduce #(let [{:keys [name val]}  %2] (assoc %1 name val)) {}
          (jdbc/query @db [(str  "SELECT * FROM " user-cache)]
                      {:row-fn decrypt-row})))
(defn get-user [name]
  (first (jdbc/query @db [(str  "SELECT * FROM " user-cache " WHERE name='" name "'")]
                     {:row-fn decrypt-row})))

(defn delete-user [name]
  (jdbc/delete! @db  user-cache ["name=?" name]))


(comment (get-users))
(comment (get-user "test3"))
(comment (delete-user "test"))
