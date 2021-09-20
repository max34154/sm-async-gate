(ns sm_async_api.dal.task
  (:require [clojure.java.jdbc :as jdbc]
            ;[clojure.spec.alpha :as s]
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
  (:import [java.sql SQLException]))


(def ^:private _default_lock_chunk_size 10)


(defmulti ^:private lock-task-sql-sch (fn [db-config] (:db-type  db-config)))

;; 
;; Database type dependend. Please add methods for each supported db 
;; 
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

;; H2 methods 
(defmethod lock-task-sql-sch "h2" [{:keys [^String db-schema]}]
  (str "UPDATE "  db-schema ".REQUEST"
       " SET status='L',locked_by=?,lock_time=SYSDATE"
       " WHERE  (status='N' or status = '') and next_run < SYSDATE and schedule_name=? limit ?"))

(defmethod lock-task-sql-nsch "h2" [{:keys [^String db-schema]}]
  (str "UPDATE "  db-schema ".REQUEST"
       " set status='L',locked_by=?,lock_time=SYSDATE"
       " WHERE  (status='N' or status = '') and next_run < SYSDATE and (schedule_name is null or schedule_name = '') limit ?"))

(defmethod lock-tasks-on-time-sql "h2" [{:keys [^String db-schema]}]
  (str "UPDATE " db-schema ".REQUEST"
       " SET status='L',lock_time=?,locked_by=?"
       " WHERE  (status='N' or status = '') and next_run < SYSDATE and %s limit ?"))
;; H2 methods END 


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
  [{:keys [^String db-schema]}]
  (let [sql (str "SELECT " g/task-field-list " FROM "
                 db-schema ".REQUEST LEFT JOIN " db-schema ".RESPONCE"
                 " ON REQ_ID=RES_REQ_ID "
                 " WHERE  (RES_REQ_ID is NULL or finished='f') and status='L' and locked_by=?")]
    (fn [^String locker]
      (jdbc/query   @db [sql locker]))))


(defn-  get-tasks-on-time-sql [{:keys [^String db-schema]}]
  (str "SELECT " g/task-field-list
       " FROM " db-schema ".REQUEST"
       " WHERE  lock_time=? and status='L' and locked_by=?"))

(defn task-reader-factory
  "Lock task for pusher  according to specified condition.
   According to prefetch strategy returns only just-locked tasks.
   Use get_task if you need all tasks locked by locker."
  [db-config]
  (let [lock-sql  (lock-tasks-on-time-sql db-config)
        get-sql (get-tasks-on-time-sql db-config)]
    (fn [^String pusher-id ^Integer chunk_size ^String condition]
      (let [lock-time (unixtime->timestamp (tod))]
        (jdbc/with-db-transaction [t-con @db]
          (when (< 0  (jdbc/execute! t-con [(format lock-sql condition)
                                            lock-time
                                            pusher-id
                                            chunk_size]))
            (jdbc/query   t-con [get-sql  lock-time pusher-id])))))))

(defn- reschedule-task-sql [{:keys [^String db-schema]}]
  (str "UPDATE " db-schema ".REQUEST "
       "SET status = case attempt >= execution_retries when true then'E'else'N'end,"
       "next_run = DATEADD(SECOND,retry_interval,next_run), "
       "attempt=attempt+1 "
       "WHERE status='L' and req_id=?"))

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

(defn add-task-result-factory
  "Insert task result generated by pusher"
  [{:keys [^String db-schema]}]
  (let [result (keyword (str db-schema ".responce"))]
    (fn [^String rec-id ^String body ^Integer status]
      (jdbc/insert! @db result {:res_req_id rec-id
                                :result body
                                :res_status status
                                :finished "t"
                                :close_time (unixtime->timestamp (tod))}))))

(defn- update-or-insert-result [t-con result row rec-id]
  (let [r (jdbc/update! t-con result row ["RES_REQ_ID=?" rec-id])]
    (if (zero? (first r))
      (jdbc/insert! t-con result row)
      r)))

(defn update-task-result-factory [{:keys [^String db-schema]}]
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
          (errorf  "Can't insert or update task %s result into DB. Error %s" rec-id (ex-message e)))))))

(defn update-task-result-and-reschedule-factory [dbconfig]
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
          (errorf  "Can't reshedule task %s . Error %s" rec-id (ex-message e)))))))

(defn post-task-result-factory
  "Write task result posted by SM. Return (nil) or throw exception"
  [{:keys [^String db-schema]}]
  (let [res-table (keyword (str db-schema ".responce"))]
    (fn
      [req] (let [{:keys [route-params body]} req]
              (jdbc/insert! @db res-table {:res_req_id (:action_id route-params)
                                           :result (slurp body)
                                           :close_time (unixtime->timestamp (tod))})))))
(defn write-task-result-factory
  "Write task result by id. Return (nil) or throw exception"
  [{:keys [^String db-schema]}]
  (let [res-table (keyword (str db-schema ".responce"))]
    (fn [^String id  ^String result]
      (jdbc/insert! @db res-table {:res_req_id id
                                   :result result
                                   :close_time (unixtime->timestamp (tod))}))))

(defn-  cleanup-exited-worker-sql [{:keys [^String db-schema]}]
  (str "MERGE INTO " db-schema ".REQUEST AS T "
       " USING ( SELECT REQ_ID, CASEWHEN(RES_REQ_ID IS NULL, 'N' , 'R') AS STATUS  FROM " db-schema ".REQUEST"
       " LEFT JOIN " db-schema ".RESPONCE"
       " ON REQ_ID=RES_REQ_ID WHERE STATUS='L' AND LOCKED_BY=? ) AS S ON T.REQ_ID=S.REQ_ID "
       "WHEN MATCHED THEN UPDATE SET T.STATUS=S.STATUS;"))

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
