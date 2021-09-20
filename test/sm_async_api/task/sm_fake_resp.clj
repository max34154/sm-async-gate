(ns sm-async-api.task.sm_fake_resp
  (:require [sm_async_api.enum.sm :as sm]
             [sm_async_api.http_errors :as http-errors]))

(def http-post-return-base
  {
   ;:opts
   ;{:basic-auth ["user-name" "user-password"]
   ; :headers {"Content-Type" "application/json", "Connection" "keep-alive"}
   ; :body "{user:{}}"
   ; :rec-id "XXxxDFTT"
   ; :method :post
   ; :url "http://212.11.152.7:13080/SM/9/rest/heartbeat"}
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

(defmacro ^:private responce [code message status]
  `(assoc http-post-return-base
          :body ~(if (nil? code) "" (format (http-post-return-base :body) message (eval code)))
          :status ~status))

(def responce-OK "Expected behavior - write and take next item from channel"
  (responce sm/RC_SUCCESS "" http-errors/OK))

(def responce-OK-RC-CANT-HAVE "Expected behavior - reschedule and take next item from channel"
  (responce sm/RC_CANT_HAVE "" http-errors/OK))

(def responce-OK-RC-VALIDATION-FAILED "Expected behavior - reschedule and take next item from channel"
  (responce sm/RC_VALIDATION_FAILED "" http-errors/OK))

(def responce-OK-RC-NOT-AUTHORIZED  
  (responce sm/RC_NOT_AUTHORIZED "" http-errors/OK))


(def responce-NOT-ATHORIZED 
  "Expected behavior - write and if thread in user-mode then take next item from channel
   else exit thread."
  (responce sm/RC_WRONG_CREDENTIALS "Not Authorized.xx" http-errors/Unathorized))

(def responce-TOO-MANY-THREADS 
  "Expected behavior - retry till ok or retry limit exceeded then reschedule and take next item from channel"
  (responce sm/RC_WRONG_CREDENTIALS "Too many ..." http-errors/Unathorized))

(def responce-UNK-ATH-ERR 
  "Expected behavior - write and take next item from channel"
  (responce nil "Too many ..." http-errors/Unathorized))

(def responce-NO-MORE 
  "Expected behavior - write and take next item from channel"
  (responce sm/RC_NO_MORE "Incorrect service name" http-errors/Not-Found))

(def responce-NO-SERVER-json 
  "Expected behavior - retry till ok or retry limit exceeded then reschedule and take next item from channel "
  (responce 4 "Too many ..." http-errors/Not-Found))

(def responce-NO-SERVER-NO-RC  
  "Expected behavior -  reschedule and  sleep than take next item from channel "
  (assoc http-post-return-base
         :body "{\"Something\":\"d\"}"
         :status http-errors/Not-Found))

(def responce-NO-SERVER-no-json "Expected behavior - SERVER-NOT-AVAILABLE???? "
  (update-in responce-NO-SERVER-json [:headers :content-type] (constantly "text/html;charset=utf-8")))

(def responce-INTERNAL-ERROR "Expected behavior - write and take next item from channel"
  (responce nil "write and go ..." http-errors/Internal-Server-Error))

(def responce-BAD-REQ "Expected behavior - write and take next item from channel"
  (responce nil "bad req write and go ..." http-errors/Bad-Request))

(def responce-INTERNAL-ERROR-GENERIC "Expected behavior - write and take next item from channel"
  (assoc responce-INTERNAL-ERROR :headers
         (assoc (responce-INTERNAL-ERROR :headers)
                :content-type  "text/html;charset=utf-8")))

(def responce-WRONG-CREDS "Expected behavior - reschedule and take next item from channel"
  (responce sm/RC_WRONG_CREDENTIALS "write and go ..." http-errors/Internal-Server-Error))

(def responce-UNK-ERROR "Expected behavior - reschedule and take next item from channel"
  (responce nil "write and go ..." 10000))

(def responce-ERROR 
  "Internal http-client error"
    (assoc http-post-return-base :error true))