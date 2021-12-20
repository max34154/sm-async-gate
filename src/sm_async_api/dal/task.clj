(ns sm_async_api.dal.task
  (:require [clojure.java.jdbc :as jdbc]
            ;[clojure.string :as str]
             ;[taoensso.timbre.appenders.core :as appenders]
            [sm_async_api.validate]
            [sm_async_api.config :as config]
            [sm_async_api.dal.globals :as g :refer [db]]
            [sm_async_api.utils.macro :refer [unixtime->timestamp tod]]
            [taoensso.timbre :as timbre
             :refer [;log  trace  debug  info  warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf
                     ;spy get-env
                     errorf]])
  (:import [java.sql SQLException BatchUpdateException]))


(def ^:private _default_lock_chunk_size 10)



;; 
;; Database type dependend. Please add methods for each supported db 
;; 

(defmulti ^:private lock-task-sql-sch (fn [db-config] (:db-type  db-config)))

(defmethod lock-task-sql-sch :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))

(defmulti ^:private lock-task-sql-nsch (fn [db-config] (:db-type  db-config)))


(defmethod lock-task-sql-nsch :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))

(defmulti ^:private lock-tasks-on-time-sql (fn [db-config] (:db-type  db-config)))

(defmethod lock-tasks-on-time-sql :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))

(defmulti ^:private cleanup-exited-worker-sql (fn [db-config] (:db-type  db-config)))

(defmethod cleanup-exited-worker-sql :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))

(defmulti ^:private post-task-result-sql (fn [db-config] (:db-type  db-config)))

(defmethod post-task-result-sql :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))

(defmulti ^:private reschedule-task-sql (fn [db-config] (:db-type  db-config)))

(defmethod reschedule-task-sql :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))


(defmulti ^:private get-tasks-on-time-sql (fn [db-config] (:db-type  db-config)))

(defmethod get-tasks-on-time-sql :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))

(defmulti ^:private update-or-insert-result-factory (fn [db-config] (:db-type  db-config)))

(defmethod update-or-insert-result-factory :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))


;; H2 methods 
(defmethod lock-task-sql-sch "h2" [{:keys [^String db-schema]}]
  (str "UPDATE "  db-schema ".REQUEST"
       " SET status='L',locked_by=?,lock_time=SYSDATE"
       " WHERE  (status='N' or status = '') and next_run < SYSDATE and schedule_name=? limit ?"))

(defmethod lock-task-sql-nsch "h2" [{:keys [^String db-schema]}]
  (str "UPDATE "  db-schema ".REQUEST"
       " SET status='L',locked_by=?,lock_time=SYSDATE"
       " WHERE  (status='N' or status = '') and next_run < SYSDATE and (schedule_name is null or schedule_name = '') limit ?"))

(defmethod lock-tasks-on-time-sql "h2" [{:keys [^String db-schema]}]
  (str "UPDATE " db-schema ".REQUEST"
       " SET status='L',lock_time=?,locked_by=?"
       " WHERE  (status='N' or status = '') and next_run < SYSDATE and %s limit ?"))

(defmethod  get-tasks-on-time-sql "h2" [db-config]
  (str "SELECT " (g/task-field-list db-config)
       " FROM " (:db-schema db-config) ".REQUEST"
       " WHERE  lock_time=? and status='L' and locked_by=?"))

(defmethod  cleanup-exited-worker-sql "h2" [{:keys [^String db-schema]}]
  (str "MERGE INTO " db-schema ".REQUEST AS T "
       " USING ( SELECT REQ_ID, CASEWHEN(RES_REQ_ID IS NULL, 'N' , 'R') AS STATUS  FROM " db-schema ".REQUEST"
       " LEFT JOIN " db-schema ".RESPONCE"
       " ON REQ_ID=RES_REQ_ID WHERE STATUS='L' AND LOCKED_BY=? ) AS S ON T.REQ_ID=S.REQ_ID "
       "WHEN MATCHED THEN UPDATE SET T.STATUS=S.STATUS;"))

(defmethod  post-task-result-sql "h2" [{:keys [^String db-schema]}]
  (str "INSERT INTO " db-schema ".RESPONCE"
       "(RES_REQ_ID, RESULT, CLOSE_TIME)"
       " VALUES(?,?,SYSDATE)"))

(defmethod reschedule-task-sql "h2" [{:keys [^String db-schema]}]
  (str "UPDATE " db-schema ".REQUEST "
       "SET status = case attempt > 1 when true then'N'else'E'end,"
       "next_run = DATEADD(SECOND,retry_interval,next_run), "
       "attempt=attempt-1 "
       "WHERE status='L' and req_id=?"))

(defmethod update-or-insert-result-factory "h2" [{:keys [^String db-schema]}]
  (let [update-sql [(str "UPDATE " db-schema ".RESPONCE "
                         " SET   BODY=?, STATUS=?, FINISHED=?, CLOSE_TIME=? "
                         " WHEN RES_REQ_ID=?")]
        insert-sql [(str "INSERT INTO " db-schema ".RESPONCE(BODY, STATUS, FINISHED, CLOSE_TIME,RES_REQ_ID) "
                         " VALUES(?,?,?,?,?)")]]
    (fn  [t-con  ^String rec-id ^String body ^Integer status ^String finished]
      (let [close_time (unixtime->timestamp (tod))
            r (jdbc/execute! t-con (conj update-sql body status finished close_time rec-id))]
        (if (zero? (first r))
          (jdbc/execute! t-con (conj insert-sql body status finished close_time rec-id))
          r)))))
;; H2 methods END 

;; Postgres methods 
(defmethod lock-task-sql-sch "postgres" [{:keys [^String db-schema]}]
  (str "UPDATE "  db-schema ".REQUEST"
       " SET status='L',locked_by=?,lock_time=CURRENT_TIMESTAMP"
       " WHERE  req_id IN "
       " ( SELECT req_id FROM " db-schema ".REQUEST"
       " WHERE (status='N' or status = '') and next_run < CURRENT_TIMESTAMP and schedule_name=? limit ?)"))

(defmethod lock-task-sql-nsch "postgres" [{:keys [^String db-schema]}]
  (str "UPDATE "  db-schema ".REQUEST"
       " SET status='L',locked_by=?,lock_time=CURRENT_TIMESTAMP"
       " WHERE  req_id IN "
       " ( SELECT req_id FROM " db-schema ".REQUEST"
       " WHERE  (status='N' or status = '') and next_run < CURRENT_TIMESTAMP and (schedule_name is null or schedule_name = '') limit ?)"))


(defmethod lock-tasks-on-time-sql "postgres" [{:keys [^String db-schema]}]
  (str "UPDATE " db-schema ".REQUEST"
       " SET status='L',lock_time=?::TIMESTAMP,locked_by=?"
       " WHERE  req_id IN "
       " ( SELECT req_id FROM " db-schema ".REQUEST"
       " WHERE  (status='N' or status = '') and next_run < CURRENT_TIMESTAMP and %s limit ?)"))

(defmethod  get-tasks-on-time-sql "postgres" [db-config]
  (str "SELECT " (g/task-field-list db-config)
       " FROM " (:db-schema db-config) ".REQUEST"
       " WHERE  lock_time=?::TIMESTAMP and status='L' and locked_by=?"))

(defmethod  cleanup-exited-worker-sql "postgres" [{:keys [^String db-schema]}]
  (str "UPDATE " db-schema ".REQUEST AS T "
       " SET STATUS=S.STATUS"
       " FROM ( SELECT REQ_ID, CASE WHEN RES_REQ_ID IS NULL THEN 'N' ELSE 'R' END AS STATUS  FROM " db-schema ".REQUEST"
       " LEFT JOIN " db-schema ".RESPONCE"
       " ON REQ_ID=RES_REQ_ID WHERE STATUS='L' AND LOCKED_BY=? ) S "
       " WHERE T.REQ_ID=S.REQ_ID"))

(defmethod  post-task-result-sql "postgres" [{:keys [^String db-schema]}]
  (str "INSERT INTO " db-schema ".RESPONCE"
       "(RES_REQ_ID, RESULT, CLOSE_TIME)"
       " VALUES(?,?,CURRENT_TIMESTAMP)"))

(defmethod reschedule-task-sql "postgres" [{:keys [^String db-schema]}]
  (str "UPDATE " db-schema ".REQUEST "
       "SET status = case attempt > 1 when true then'N'else'E'end,"
       "next_run = next_run + retry_interval * interval '1 second', "
       "attempt=attempt-1 "
       "WHERE status='L' and req_id=?"))

(defmethod update-or-insert-result-factory "postgres" [{:keys [^String db-schema]}]
  (let [update-sql [(str "UPDATE " db-schema ".RESPONCE "
                         " SET   BODY=?, RES_STATUS=?, FINISHED=?, CLOSE_TIME=?::TIMESTAMP "
                         " WHEN RES_REQ_ID=?")]
        insert-sql [(str "INSERT INTO " db-schema ".RESPONCE(BODY, RES_STATUS, FINISHED, CLOSE_TIME,RES_REQ_ID) "
                         " VALUES(?,?,?,?::TIMESTAMP,?)")]]
    (fn  [t-con  ^String rec-id ^String body ^Integer status ^String finished]
      (let [close_time (unixtime->timestamp (tod))
            r (jdbc/execute! t-con (conj update-sql body status finished close_time rec-id))]
        (if (zero? (first r))
          (jdbc/execute! t-con (conj insert-sql body status finished close_time rec-id))
          r)))))

;; Postgres methods END 


;;
;; Database typed independed request. At least I think so. 
;;
;;

(defn lock-tasks-factory
  "  Lock tasks for external worker locker, whoes use API to lock tasks
     If schedule-name specified - select tasks from this shedule only, otherwise any tasks without schedule.
     If chunk_size specified - maximum number of selected task is chunk_size, otherwise - _default_lock_chunk_size
     Return number of locked tasks "
  [db-config]
  (let [sql-sch  (lock-task-sql-sch  db-config)
        sql-nsch  (lock-task-sql-nsch  db-config)]
    (fn [^String locker  ^String schedule-name ^Integer chunk_size]
      (let [chunk_size (or chunk_size _default_lock_chunk_size)]
        (first
         (if (nil? schedule-name)
           (jdbc/execute!   @db [sql-nsch locker chunk_size])
           (jdbc/execute!   @db [sql-sch locker schedule-name chunk_size])))))))


(defn user-lock-condition
  "All the tasks created by particular user  exclude tasks in ScheduleOnly mode"
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
;
; By default any result record means that task process are finished.
; Intermidiate result should have finished filed with 'f'
;

(defn get-tasks-factory
  "Select tasks locked for worker"
  [db-config]
  (let [db-schema (:db-schema db-config)
        sql (str "SELECT " (g/task-field-list db-config) " FROM "
                 db-schema ".REQUEST LEFT JOIN " db-schema ".RESPONCE"
                 " ON REQ_ID=RES_REQ_ID "
                 " WHERE  (RES_REQ_ID is NULL or finished='f') and status='L' and locked_by=?")]
    (fn [^String locker]
      (try
        (jdbc/query   @db [sql locker])
        (catch Exception e (errorf "Exception %s for %s" (ex-message e) sql))))))




(defn task-reader-factory
  "Lock task for pusher  according to specified condition.
   According to prefetch strategy returns only just-locked tasks.
   Use get_task if you need all tasks locked by locker."
  [db-config]
  (let [lock-sql  (lock-tasks-on-time-sql db-config)
        get-sql (get-tasks-on-time-sql db-config)]
    ;(timbre/debug "!!! - -- Get task sql " get-sql)
    (fn [^String pusher-id ^Integer chunk_size ^String condition]
      (let [lock-time (unixtime->timestamp (tod))]
        (jdbc/with-db-transaction [t-con @db]
          (when (< 0  (first (jdbc/execute! t-con [(format lock-sql condition)
                                                   lock-time
                                                   pusher-id
                                                   chunk_size])))
            (jdbc/query   t-con [get-sql  lock-time pusher-id])))))))



(defn reschedule-task-factory [dbconfig]
  (let [sql (reschedule-task-sql dbconfig)]
    (fn [^String id]
      (jdbc/execute! @db  [sql id]))))


(defn unlock-task-factory
  "Just remove task lock"
  [{:keys [^String db-schema]}]
  (let [request (keyword (str db-schema ".request"))]
    (fn [^String id]
      (jdbc/update!  @db request {:status "N"} ["status='L' and req_id=?" id]))))

(defn clear-locks-factory
  "Remove lock mark from all unfinished tasks"
  [{:keys [^String db-schema]}]
  (fn [] (jdbc/execute!  @db
                         (str "UPDATE " db-schema ".REQUEST set status='N' "
                              "WHERE req_id in "
                              "(SELECT req_id FROM " db-schema ".REQUEST"
                              " LEFT JOIN " db-schema ".RESPONCE ON REQ_ID=RES_REQ_ID "
                              " WHERE  STATUS='L' AND (RES_REQ_ID is NULL or finished='f'))"))))

#_(defn add-task-result-factory
  "Insert task result generated by pusher"
  [{:keys [^String db-schema]}]
  (let [result (keyword (str db-schema ".responce"))]
    (fn [^String rec-id ^String body ^Integer status]
      (jdbc/insert! @db result {:res_req_id rec-id
                                :result body
                                :res_status status
                                :finished "t"
                                :close_time (unixtime->timestamp (tod))}))))
(defn add-task-result-factory
  "Insert task result generated by pusher"
  [{:keys [^String db-schema ^String db-type]}]
  (let [sql (case db-type
              "postgres"
              (str "INSERT INTO " db-schema ".RESPONCE"
                   "(RES_REQ_ID, RESULT, RES_STATUS, FINISHED, CLOSE_TIME) "
                   "VALUES(?,?,?,'t',?::TIMESTAMP)")
              (str "INSERT INTO " db-schema ".RESPONCE"
                   "(RES_REQ_ID, RESULT, RES_STATUS, FINISHED, CLOSE_TIME) "
                   "VALUES(?,?,?,'t',?)"))]
    (fn [^String rec-id ^String body ^Integer status]
      (jdbc/execute! @db [sql rec-id body status (unixtime->timestamp (tod))]))))

#_(defn- update-or-insert-result [t-con result row rec-id]
    (let [r (jdbc/update! t-con result row ["RES_REQ_ID=?" rec-id])]
      (if (zero? (first r))
        (jdbc/insert! t-con result row)
        r)))



#_(defn update-task-result-factory [{:keys [^String db-schema]}]
  (let [result (keyword (str db-schema ".responce"))]
    (fn  [^String rec-id ^String body ^Integer status ^String finished]
      (try
        (jdbc/with-db-transaction [t-con @db]
          (update-or-insert-result t-con result {:res_req_id rec-id
                                                 :result body
                                                 :res_status status
                                                 :finished finished
                                                 :close_time (unixtime->timestamp (tod))}
                                   rec-id))

        (catch SQLException e
          (errorf  "Can't insert or update task %s result into DB. Error %s" rec-id (ex-message e)))
        (catch BatchUpdateException e
          (errorf  "Can't insert or update task %s result into DB. Error %s" rec-id (ex-message e)))))))

(defn update-task-result-factory [db-config]
    (let [ update-or-insert-result (update-or-insert-result-factory db-config)]
      (fn  [^String rec-id ^String body ^Integer status ^String finished]
        (try
          (jdbc/with-db-transaction [t-con @db]
            (update-or-insert-result t-con  rec-id body status finished))
          (catch SQLException e
            (errorf  "Can't insert or update task %s result into DB. Error %s" rec-id (ex-message e)))
          (catch BatchUpdateException e
            (errorf  "Can't insert or update task %s result into DB. Error %s" rec-id (ex-message e)))))))


#_(defn update-task-result-and-reschedule-factory [dbconfig]
  (let [result (keyword (str (:db-schema dbconfig) ".responce"))
        sql (reschedule-task-sql dbconfig)]
    (fn
      [^String rec-id ^String body ^Integer status]
      (try
        (jdbc/with-db-transaction [t-con @db]
          (update-or-insert-result t-con result {:res_req_id rec-id
                                                 :result body
                                                 :res_status status
                                                 :finished "f"
                                                 :close_time (unixtime->timestamp (tod))}
                                   rec-id)
          (jdbc/execute! @db  [sql rec-id]))
        (catch SQLException e
          (errorf  "Can't reshedule task %s . Error %s" rec-id (ex-message e)))
        (catch BatchUpdateException e
          (errorf  "Can't reshedule task %s . Error %s" rec-id (ex-message e)))))))

(defn update-task-result-and-reschedule-factory [db-config]
  (let [update-or-insert-result (update-or-insert-result-factory db-config)
        sql (reschedule-task-sql db-config)]
    (fn
      [^String rec-id ^String body ^Integer status]
      (try
        (jdbc/with-db-transaction [t-con @db]
            (update-or-insert-result t-con  rec-id body status "f")                      
          (jdbc/execute! @db  [sql rec-id]))
        (catch SQLException e
          (errorf  "Can't reshedule task %s . Error %s" rec-id (ex-message e)))
        (catch BatchUpdateException e
          (errorf  "Can't reshedule task %s . Error %s" rec-id (ex-message e)))))))

#_(defn post-task-result-factory
    "Write task result posted by SM. Return (nil) or throw exception"
    [{:keys [^String db-schema]}]
    (let [res-table (keyword (str db-schema ".responce"))]
      (fn
        [req] (let [{:keys [route-params body]} req]
                (jdbc/insert! @db res-table {:res_req_id (:action_id route-params)
                                             :result (slurp body)
                                             :close_time (unixtime->timestamp (tod))})))))
(defn post-task-result-factory
  "Write task result posted by SM. Return (nil) or throw exception"
  [db-config]
  (let [sql (post-task-result-sql db-config)]
    (fn
      [req] (let [{:keys [route-params body]} req]
              (jdbc/execute! @db [sql
                                  (:action_id route-params)
                                  (slurp body)])))))

#_(defn write-task-result-factory
    "Write task result by id. Return (nil) or throw exception"
    [{:keys [^String db-schema]}]
    (let [res-table (keyword (str db-schema ".responce"))]
      (fn [^String id  ^String result]
        (jdbc/insert! @db res-table {:res_req_id id
                                     :result result
                                     :close_time (unixtime->timestamp (tod))}))))
(defn write-task-result-factory
  "Write task result by id. Return (1) or throw exception"
  [db-config]
  (let [sql (post-task-result-sql db-config)]
    (fn [^String id  ^String result]
      (jdbc/execute! @db [sql id result]))))

(defn cleanup-exited-worker-factory
  "Unlock all no finished tasks for worker.
   Return number of unlocked tasks"
  [db-config]
  (let [sql (cleanup-exited-worker-sql db-config)]
    (fn [^String worker]
      (first (jdbc/execute! @db [sql worker])))))


(defn- posted-results-sql [{:keys [^String db-schema]}]
  (str "SELECT REQ_ID FROM " db-schema ".RESPONCE"
       " INNER JOIN "  db-schema ".REQUEST ON REQ_ID=RES_REQ_ID "
       " WHERE locked_by=?"))

(defn get-worker-results-factory
  [db-config]
  (let [sql (posted-results-sql db-config)]
    (fn [^String worker]
      (jdbc/query @db [sql worker]))))

(defn get-worker-results
  "Select results posted by worker. For test only"
  [worker]
  ((get-worker-results-factory (:database @config/config)) worker))


(defn configure [db-config]
  {:lock (lock-tasks-factory db-config)
   :get  (get-tasks-factory db-config)
   :task-reader  (task-reader-factory db-config)
   :reschedule (reschedule-task-factory db-config)
   :unlock (unlock-task-factory db-config)
   :clear-locks (clear-locks-factory db-config)
   :add-result (add-task-result-factory db-config)
   :update-result (update-task-result-factory db-config)
   :update-result-and-reschedule (update-task-result-and-reschedule-factory db-config)
   :post-resp (post-task-result-factory db-config)  ; write result posted by SM using responce row
   :post-result (write-task-result-factory db-config) ;write result using id and result string
   :cleanup (cleanup-exited-worker-factory db-config)
   :get-worker-results (get-worker-results-factory db-config)})

(comment 
  (configure {:db-schedule "ASYNC" :db-type "postgres"})
  (configure {:db-schedule "ASYNC" :db-type "h2"}))
