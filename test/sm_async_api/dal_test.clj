(ns ^:dal sm-async-api.dal-test
  (:require
   ;[cheshire.core :as json]
   [clojure.test :refer [testing use-fixtures deftest is]]
   [clj-time.coerce :as clj-time]
   [sm_async_api.dal :refer [lock_tasks
                             cleanup_exited_worker
                             get_tasks
                             post_task_result
                             get_worker_results
                             check_db
                             open_db
                             get_action
                             reschedule_task
                             execute_script]])
  #_{:clj-kondo/ignore [:unused-import]}
  (:import [java.sql SQLException SQLIntegrityConstraintViolationException]))

(def test_user  "max")
(def test_schedule "test_sch")
(def test_chunk_size 3)

(def test_db
  {:classname   "org.h2.Driver"
   :subprotocol "h2:mem"
   :subname     "demo;DB_CLOSE_DELAY=-1"
   :user        "sa"
   :password    ""})

(def test_data (slurp "test/sm_async_api/test_data.sql"))

(defn fix-test-db [t]
  (open_db test_db)
  (check_db)
  (t))

(defn fix-test-data  [t]
  (execute_script test_data)
  (t)
  (execute_script "TRUNCATE TABLE ASYNC.ATTACHMENT;TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;"))

(use-fixtures :once fix-test-db)

(use-fixtures :each fix-test-data)


(deftest test-lock-and-cleanup-tasks
  (testing (format "Lock 3 tasks for user %s and schedule %s" test_user test_schedule)
    (is (= test_chunk_size ((lock_tasks test_user test_schedule test_chunk_size) 0))))

  (testing (format "Reliase all tasks for user %s and schedule %s" test_user test_schedule)
    (is (= test_chunk_size ((cleanup_exited_worker test_user) 0)))))

(deftest test-standart-task-operations
  (testing (format "Lock 3 tasks for user %s and schedule %s " test_user test_schedule)
    (is (= test_chunk_size ((lock_tasks test_user test_schedule test_chunk_size) 0))))

  (testing "Get tasks"
    (is (= 3 (count  (get_tasks test_user)))))

  (testing "Write result"
    (let [result
          (post_task_result (:req_id (first
                                      (get_tasks test_user)))
                            "{\"Result Code\": 0}")]
      (is (= [1] result))))

  (testing "Attempt to write result twice for same task"
    (is (thrown? SQLIntegrityConstraintViolationException
                 (post_task_result  (:req_id (first (get_worker_results test_user)))
                                    "{\"Result Code\": 0}")))))

(defn check_reschedule  [prev_req_id prev-att  prev-next_run prev-retry_interval]
  (reschedule_task prev_req_id)
  (let [{:keys [req_id attempt next_run]}
        (first (get_action {:route-params {:action_id prev_req_id}}))]
    ;(println "old vals req_id " prev_req_id " attempt " prev-att "next_run" (clj-time/to-long prev-next_run) " prev-retry_interval " prev-retry_interval)
    ;(println "new vals req_id " req_id " attempt " attempt "next_run" (clj-time/to-long next_run))
    (and (not (nil? req_id))
         (= (- attempt  prev-att) 1)
         (= (- (clj-time/to-long next_run)
               (clj-time/to-long prev-next_run))
            (* prev-retry_interval 1000)))))

(deftest test-lock-and-reliase-task
  (println "tasks locked" (lock_tasks test_user test_schedule 1))
  (let [{:keys [req_id attempt next_run retry_interval]}
        (first (get_tasks test_user))
        result (if (nil? req_id) false
                   (check_reschedule  req_id attempt next_run retry_interval))]
    (is (= true result))))


