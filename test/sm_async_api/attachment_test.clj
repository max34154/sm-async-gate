(ns ^:attachment sm-async-api.attachment-test
  (:require
   [clojure.test :refer [testing use-fixtures deftest is are]]
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

(def test-path "test/config/run/")


(defn base-url []  (str "http://" (-> @config/config :config :module_ip) (-> @config/config :config :base-path)))

(defn async-url [] (-> @config/config :config :async-action-url))

(defn set-url []
  (str "http://" (-> @config/config :config :module_ip) (-> @config/config :config :base-path)
       "test-service/SDtest/test-action"))


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


(def test_data (slurp "test/config/run/test_data.sql"))

(def test-authorization  "QmFzaWMgbWF4OlNoaXMhITlTRDIzNGRzMTIz")



(defn fix-read-config [t]
  (timbre/set-level! :debug)
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

(defn build-opts [rec-id thread athorization mode subject]
  {:rec-id rec-id
   :mode mode
   :thread thread
   :headers {"Authorization" athorization}
   :service "FOSeaist"
   :subject subject
   :url (set-url)})


(defmacro user-mode-opts [rec-id]
  `(build-opts ~rec-id "test-thread" test-authorization :user-mode "SDtest"))

(defmacro async-mode-opts [rec-id]
  `(build-opts ~rec-id "test-thread" test-authorization :async-mode "ASYNCID"))

(def test-body-user-mode (json/generate-string {:Messages ["Sample Message"]
                                                :ReturnCode 0
                                                :Interaction {:Otherfield 1
                                                              :InteractionID  "SDtest"
                                                              :OneMorefield 2}}))

(def test-body-async-mode (json/generate-string {:Messages ["Sample Message"]
                                                 :ReturnCode 0
                                                 :Async {:Otherfield 1
                                                         :ActionID  "ASYNCID"
                                                         :OneMorefield 2}}))

(deftest test-build-attachment-url
  (testing "Build attachment sync mode"
    (testing " having subject in body"
      (let [opts (user-mode-opts "test-rec-id")]
        (is (= (str (base-url) (opts :service) "/" (opts :subject) "/attachments")
               (attachment/build-attachment-url opts (json/parse-string test-body-user-mode))))))
    (testing " not having subject in body"
      (let [opts (user-mode-opts "test-rec-id")]
        (is (= (str (base-url) (opts :service) "/" (opts :subject) "/attachments")
               (attachment/build-attachment-url (dissoc opts :subject) (json/parse-string test-body-user-mode)))))))
  (testing "Build attachment async mode"
    (let [opts (async-mode-opts "test-rec-id")]
      (is (= (str (async-url)  "/" (opts :subject) "/attachments")
             (attachment/build-attachment-url opts (json/parse-string test-body-async-mode)))))))


(deftest test-copy-OK
  (relector-set-responce responce-NO-SERVER-NO-RC)
  (testing (format "Succesfull attachment copy %s mode"
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)
          res (attachment/copy  (user-mode-opts "test13")  test-body-user-mode)]
      ;(println "RES "  (json/generate-string res))
      (is (= list-size (count-codes res 404))))))

(deftest test-copy-GENERIC
  (testing (format "Attachment copy in %s mode."
                   (-> @config/config :executors-globals :attachment-copy-mode))
    (let [attachment-list  (@attachment/get-attachments-by-req-id   "test13")
          list-size (count attachment-list)]
      (are  [status responce] (= list-size (do
                                             (relector-set-responce responce)
                                             (count-codes
                                              (attachment/copy  (user-mode-opts "test13")  test-body-user-mode)
                                              status)))
        200 (reflector-200-attached)
        404 responce-NO-SERVER-NO-RC
        401 responce-TOO-MANY-THREADS
        401 responce-NOT-ATHORIZED
        500 responce-INTERNAL-ERROR
        500 responce-WRONG-CREDS
        400 responce-BAD-REQ
        10000 responce-UNK-ERROR))))