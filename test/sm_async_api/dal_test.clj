(ns ^:dal sm-async-api.dal-test
  (:require
   [clojure.test :refer [testing use-fixtures deftest is]]
   [clj-time.coerce :as clj-time]
   [sm_async_api.config :as config]
   [sm_async_api.dal.configure :refer [configure-database execute-script]]
   [sm_async_api.dal.globals :refer [task-action request-action]])
  #_{:clj-kondo/ignore [:unused-import]}
  (:import [java.sql SQLException SQLIntegrityConstraintViolationException]))

(def test_user  "max")
(def test_schedule "test_sch")
(def test_chunk_size 3)


(def test_data (slurp "test/sm_async_api/test_data.sql"))

(defn fix-test-db [t]
  (config/configure "test/")
  (configure-database)
  (t))

(defn fix-test-data  [t]
  (execute-script "TRUNCATE TABLE ASYNC.ATTACHMENT;TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;")
  (execute-script test_data)
  (t))

(use-fixtures :once fix-test-db)

(use-fixtures :each fix-test-data)

(deftest test-lock-and-cleanup-tasks
  (testing "Lock and cleanup"
    (let [lock-tasks (@task-action :lock)
          cleanup-exited-worker (@task-action :cleanup)]
      (testing (format "Lock 3 tasks for user %s and schedule %s" test_user test_schedule)
        (is (= test_chunk_size (lock-tasks test_user test_schedule test_chunk_size))))

      (testing (format "Reliase all tasks for user %s and schedule %s" test_user test_schedule)
        (is (= test_chunk_size (cleanup-exited-worker test_user)))))))

(deftest test-standart-task-operations
  (testing "Standart operations"
    (let [lock-tasks (@task-action :lock)
          get-tasks (@task-action :get)
          post-task-result (@task-action :post-result)]
      (testing (format "Lock 3 tasks for user %s and schedule %s " test_user test_schedule)
        (is (= test_chunk_size (lock-tasks test_user test_schedule test_chunk_size))))

      (testing "Get tasks"
        (is (= 3 (count  (get-tasks test_user)))))

      (testing "Write result"
        (let [result
              (first (post-task-result (:req_id (first
                                                 (get-tasks test_user)))
                                       "{\"Result Code\": 0}"))]
          (is (= nil result))))

      (testing "Attempt to write result twice for same task"
        (is (thrown? SQLIntegrityConstraintViolationException
                     (post-task-result  (:req_id (first ((@task-action :get-worker-results)  test_user)))
                                        "{\"Result Code\": 0}")))))))

(defn check_reschedule  [prev_req_id prev-att  prev-next_run prev-retry_interval]
  ((@task-action :reschedule) prev_req_id)
  (let [{:keys [req_id attempt next_run]}
        (first ((@request-action :get) {:route-params {:action_id prev_req_id}}))]
    (and (not (nil? req_id))
         (= (- attempt  prev-att) 1)
         (= (- (clj-time/to-long next_run)
               (clj-time/to-long prev-next_run))
            (* prev-retry_interval 1000)))))

(deftest test-lock-and-release-task
  (testing "Lock and release"
    ((@task-action :lock) test_user test_schedule test_chunk_size)
    (let [{:keys [req_id attempt next_run retry_interval]}
          (first ((@task-action :get) test_user))
          result (if (nil? req_id) false
                     (check_reschedule  req_id attempt next_run retry_interval))]
      (is (= true result)))))


