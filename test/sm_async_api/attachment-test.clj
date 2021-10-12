(ns ^:attachment sm-async-api.attachment-test
  (:require
   [clojure.test :refer [testing use-fixtures deftest is are]]
   [clojure.string :as s]
   [cheshire.core :as json]
   [sm_async_api.enum.sm :as sm]
   [sm_async_api.task.attachment :as attachment]
   [sm_async_api.config :as config]
   [sm-async-api.task.sm_fake_resp :refer [http-post-return-base
                                           responce-INTERNAL-ERROR
                                           responce-TOO-MANY-THREADS
                                           responce-NOT-ATHORIZED
                                           responce-NO-SERVER-NO-RC
                                           responce-BAD-REQ
                                           responce-WRONG-CREDS
                                           responce-UNK-ERROR]]
   [sm_async_api.utils.reflector :refer [relector-set-responce
                                         reflector-start
                                         reflector-stop]]
   [sm_async_api.dal.configure :refer [configure-database
                                       execute-script]]
   [taoensso.timbre :as timbre]))

(def test-path "test/")


(defn set-url []
  (str "http://" (-> @config/config :config :module_ip) (-> @config/config :config :base-path)
       "testservice/SDtest/attachments"))

(def test-users ["max", "test", ["dima","vs"], ["other1", "other2"]])


(defmacro reflector-200-NO-MORE []
  `(relector-set-responce (assoc http-post-return-base :body (str
                                                              "{\"Messages\": [],"
                                                              "\"ReturnCode\":" sm/RC_NO_MORE "}"))))


(defmacro reflector-200-N []
  `(relector-set-responce (assoc http-post-return-base :body (str
                                                              "{\"Messages\": [],"
                                                              "\"ReturnCode\":" -1 "}"))))

(defmacro reflector-200-attached []
  `(relector-set-responce (assoc http-post-return-base :body
                                 (str
                                  "{\"Messages\": [],"
                                  "\"ReturnCode\": 0,"
                                  "\"attachment\": {"
                                  "\"href\": \"cid:6163d9fe001ec0c1805e8328\","
                                  "\"len\": 41471,"
                                  "\"name\": \"PHOTO-2021-10-10-17-17-28 2.jpg\","
                                  "\"type\": \"application/octet-stream\","
                                  "\"xmime:contentType\": \"application/octet-stream\""
                                  "}}"))))

(defmacro reflector-NOT-OK
  ([]
   `(reflector-NOT-OK 500))
  ([code]
   `(relector-set-responce (assoc http-post-return-base :body "{}" :status ~code))))


(def test_data (slurp "test/sm_async_api/test_data.sql"))

(def test-authorization  "QmFzaWMgbWF4OlNoaXMhITlTRDIzNGRzMTIz")



(defn fix-read-config [t]
  (timbre/set-level! :fatal)
  (config/configure test-path)
  (configure-database)
  (execute-script "TRUNCATE TABLE ASYNC.HOOK;")
  (reflector-start)
  (t)
  (reflector-stop))

(defn fix-test-data [t]
  (execute-script "TRUNCATE TABLE ASYNC.ATTACHMENT; TRUNCATE TABLE ASYNC.MESSAGE; TRUNCATE TABLE ASYNC.HOOK; TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;")
  (execute-script test_data)

  (swap! config/config update-in [:executors-globals :attachment-copy-mode] (fn [_] "fast"))
  (timbre/debugf  "Test in  %s mode"
                  (-> @config/config :executors-globals :attachment-copy-mode))
  (t)

  (swap! config/config update-in [:executors-globals :attachment-copy-mode] (fn [_] "slow"))
  (timbre/debugf  "Test in  %s mode"
                  (-> @config/config :executors-globals :attachment-copy-mode))
  (t))

(use-fixtures :once fix-read-config)

(use-fixtures :each fix-test-data)


(defn count-codes [list code]
  (reduce #(if (= (:status %2) code) (inc %1) %1) 0 list))

(deftest test-copy-OK
  (relector-set-responce (reflector-200-attached))
  (testing (format "Succesfull attachment copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      ;(println "RES "  (json/generate-string res))
      (is (= list-size (count-codes res 200))))))


(deftest test-copy-NO-SERVER-NO-RC
  (relector-set-responce responce-NO-SERVER-NO-RC)
  (testing (format "Fail copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      (is (= list-size (count-codes res 404))))))


(deftest test-copy-TOO-MANY-THREADS
  (relector-set-responce responce-TOO-MANY-THREADS)
  (testing (format "Fail copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      (is (= list-size (count-codes res 401))))))


(deftest test-copy-NOT-ATHORIZED
  (relector-set-responce responce-NOT-ATHORIZED)
  (testing (format "Fail copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      (is (= list-size (count-codes res 401))))))


(deftest test-copy-BAD-REQ
  (relector-set-responce responce-BAD-REQ)
  (testing (format "Fail copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      (is (= list-size (count-codes res 400))))))


(deftest test-copy-INTERNAL-ERROR
  (relector-set-responce responce-INTERNAL-ERROR)
  (testing (format "Fail copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      (is (= list-size (count-codes res 500))))))


(deftest test-copy-WRONG-CREDS
  (relector-set-responce responce-WRONG-CREDS)
  (testing (format "Fail copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      (is (= list-size (count-codes res 500))))))


(deftest test-copy-UNK-ERROR
  (relector-set-responce responce-UNK-ERROR)
  (testing (format "Fail copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy "test13" "test-thread" (set-url) test-authorization)]
      (is (= list-size (count-codes res 10000))))))