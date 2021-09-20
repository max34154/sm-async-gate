(ns sm-async-api.task.process_result_test
  (:require

   [sm_async_api.task.process_result :as pr :refer [OK
                                                    TOO-MANY-THREADS
                                                    SERVER-NOT-AVAILABLE
                                                    NOT-ATHORIZED]]
   [sm_async_api.enum.sm :as sm]
   [clojure.test :refer [testing  deftest  are]]
   [sm_async_api.task.writers :as tw]
   [sm_async_api.http_errors :as http-errors]))


(def http-post-return-base
  {:opts
   {:basic-auth ["user-name" "user-password"]
    :headers {"Content-Type" "application/json", "Connection" "keep-alive"}
    :body "{user:{}}"
    :rec-id "XXxxDFTT"
    :method :post
    :url "http://212.11.152.7:13080/SM/9/rest/heartbeat"}
   :body
   "{\n  \"Messages\": [\"%s\"],\n  \"ReturnCode\": %s,\n  \"user\": {\"thread_info\": \"Thread:17b4ed7b88a:288 Seq: 3\"}\n}"
   :headers
   {:connection "Keep-Alive"
    :content-length "99"
    :content-type "application/json;charset=utf-8"
    :date "Mon, 16 Aug 2021 12:04:33 GMT"
    :keep-alive "timeout=60, max=100"
    :server "WEB"
    :x-content-type-options "nosniff"}
   :status 200})

(defmacro responce [code message status]
  `(assoc http-post-return-base
          :body ~(if (nil? code) "" (format (http-post-return-base :body) message (eval code)))
          :status ~status))

(defn get-test-result-writer []
  (fn [rec-id body status]
    (str  "XXXWrite rec-id=" rec-id  "body" body "state=" status)))

(defn get-test-action-rescheduler [] 
  (fn [rec-id body status]
  (str  "XXXReschedule if possible rec-id=" rec-id   "body" body "state=" status)))


(def responce-OK (responce sm/RC_SUCCESS "" http-errors/OK))

(def responce-NOT-ATHORIZED
  (responce sm/RC_WRONG_CREDENTIALS "Not Authorized.xx" http-errors/Unathorized))

(def responce-TOO-MANY-THREADS
  (responce sm/RC_WRONG_CREDENTIALS "Too many ..." http-errors/Unathorized))

(def responce-UNK-ATH-ERR
  (responce nil "Too many ..." http-errors/Unathorized))

(def responce-NO-MORE
  (responce sm/RC_NO_MORE "Incorrect service name" http-errors/Not-Found))

(def responce-NO-SERVER-json
  (responce nil "Too many ..." http-errors/Not-Found))

(def responce-NO-SERVER-no-json
  (assoc responce-NO-SERVER-json :headers
         (assoc (responce-NO-SERVER-json :headers)
                :content-type  "text/html;charset=utf-8")))

(def responce-INTERNAL-ERROR
  (responce nil "write and go ..." http-errors/Internal-Server-Error))

(def responce-BAD-REQ
  (responce nil "bad req write and go ..." http-errors/Bad-Request))

(def responce-INTERNAL-ERROR-GENERIC
  (assoc responce-INTERNAL-ERROR :headers
         (assoc (responce-INTERNAL-ERROR :headers)
                :content-type  "text/html;charset=utf-8")))

(def responce-WRONG-CREDS
  (responce sm/RC_WRONG_CREDENTIALS "write and go ..." http-errors/Internal-Server-Error))

(def responce-UNK-ERROR
  (responce nil "write and go ..." 10000))

(deftest  test-process-result
  (testing "Check process-result (analyse results recived from sm) "
    (with-redefs  [sm_async_api.task.writers/result-writer (delay (get-test-result-writer))
                   sm_async_api.task.writers/action-rescheduler  (delay (get-test-action-rescheduler))]
      (are  [r p] (= r (pr/process p))
        OK responce-OK
        NOT-ATHORIZED responce-NOT-ATHORIZED
        TOO-MANY-THREADS responce-TOO-MANY-THREADS
        OK responce-UNK-ATH-ERR
        OK responce-NO-MORE
        SERVER-NOT-AVAILABLE responce-NO-SERVER-json
        SERVER-NOT-AVAILABLE responce-NO-SERVER-no-json
        OK responce-BAD-REQ
        OK responce-INTERNAL-ERROR
        OK responce-INTERNAL-ERROR-GENERIC
        OK  responce-WRONG-CREDS
        OK responce-UNK-ERROR))))




