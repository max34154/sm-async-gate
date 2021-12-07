(ns sm_async_api.task.process_result
  (:require
   [cheshire.core :as json]
   [clojure.string :as s]
   #_{:clj-kondo/ignore [:refer-all]}
   [sm_async_api.enum.process_result :refer :all]
   [sm_async_api.enum.sm :as sm]
   [sm_async_api.http_errors :as http-errors]
   [sm_async_api.task.writers :as tw]
   [sm_async_api.utils.macro :refer [_case]]
   [sm_async_api.utils.sm_resp_decode :refer [get-RC get-jbody]]
   [taoensso.timbre.appenders.core :as appenders]
   [sm-async-api.hook.hook :as hook]
   [taoensso.timbre :as timbre
    :refer [debug warn fatal]]))

(defmacro ^:private tread-details
  ([opts] `(format "Thread:%s,Mode:%s,User:%s,RecID:%s,Url:%s"
                   (:thread ~opts) (:mode ~opts)
                   (:user-name ~opts) (:rec-id ~opts) (:url ~opts)))

  ([prev opts  post]
   `(str (if (nil? ~prev) "" ~prev)
         (tread-details ~opts)
         (if (nil? ~post) "" ~post))))


(defn- finalize-action-OK [body headers status opts]
  (when (string? (@tw/result-writer (opts :rec-id) body status))
    (timbre/error
     (tread-details  "Write error in status http-errors/OK"
                     opts
                     (str "Responce body " body))))
  (hook/post-and-copy opts status body  headers))


(defn- finalize-action-ERROR [body headers status opts]
  (when (string? (@tw/result-writer (opts :rec-id) body status))
    (timbre/error
     (tread-details  "Write error in status http-errors/OK"
                     opts
                     (str "Responce body " body))))
  (hook/post-message opts status body (:content-type headers)))


(defn reschedule-action [body headers status opts]
  (when (string? (@tw/action-rescheduler (opts :rec-id) body status))
    (timbre/error
     (tread-details  "Write error in status http-errors/OK"
                     opts
                     (str "Responce body " body))))
  (when (= (:attempt opts) 1) ;; it was last attempt, inform about fail
    (hook/post-message opts status body (:content-type headers))))

(defn- process-http-ok [body headers status opts]
  (if (= (get-RC body headers) sm/RC_SUCCESS)
    (finalize-action-OK body headers status opts)
    (reschedule-action body headers status opts))
  OK)


(defn- process-http-unathorized [body headers status opts]
  (let [jbody (get-jbody body headers)]
    (if  (= (get jbody "ReturnCode") sm/RC_WRONG_CREDENTIALS)
      (if (and (some? (jbody "Messages"))
               (s/includes? ((jbody "Messages") 0) "Not Authorized"))
        (do
          (timbre/error "Not Authorized. Write the answer. Body:"  body)
          (finalize-action-ERROR body headers status opts)
          NOT-ATHORIZED)

        (do
          (warn "Two many threads." (tread-details   opts))
          TOO-MANY-THREADS))
      (do
        (timbre/error "Unkown athorization error:"  body)
        (finalize-action-ERROR body headers status opts)
        OK))))

(defn- process-http-not-found [body headers status opts]
  (_case  (get-RC body headers)
          sm/RC_NO_MORE (do ; sm responce - has not requested item 
                          (timbre/error "Attempt to use incorrect service name:" body)
                          (finalize-action-ERROR body headers status opts)
                          OK)

          nil (do ; ReturnCode is nil, most probably no SM
                (timbre/error (tread-details "SM not available - " opts "."))
                SERVER-NOT-AVAILABLE)

          (do (warn (tread-details
                     (format "Suspections combination of status 404 and ReturnCode %s"
                             (get (json/parse-string body) "ReturnCode"))
                     opts "."))
                          ;cheat - this code reused to retry action 
              TOO-MANY-THREADS)))


(defn- process-http-internal-error [body headers status opts]
  (if (= (get-RC body headers) sm/RC_WRONG_CREDENTIALS)
    (reschedule-action body headers status opts)
    (finalize-action-ERROR body headers status opts))
  OK)

(defn process
  "Process responce from SM. 
   Return code must by processed by push managers
   Responce matrix 
   !status         !ReturnCode !    -- Message --    ! Action ! Return code            
   !OK 200         ! SUCCESS 0 !      ANY            !   W    ! OK             
   !OK 200         ! not 0     !      ANY            !   R    ! OK             
   !Unathorized 401! WRONG_C -4! 'Not Authorized'    !   W    ! NOT-ATHORIZED  
   !Unathorized 401! WRONG_C -4! not 'Not Authorized'!   -    ! TOO-MANY-THREADS
   !Unathorized 401! not -4    !      ANY            !   W    ! ОК
   !Not-Found   404! NO_MORE  9!      ANY            !   W    ! ОК             
   !Not-Found   404! not      9!      ANY            !   -    ! TOO-MANY-THREADS 
   !Not-Found   404! nil       !      ANY            !   -    ! SERVER-NOT-AVAILABLE 
   !Bad-Request 400! ANY       !      ANY            !   W    ! ОК             
   !Not-Found   500! WRONG_C -4!      ANY            !   R    ! OK 
   !Not-Found   500!  not -4   !      ANY            !   W    ! OK 
   !ANY OTHER      !   ANY     !      ANY            !   W    ! OK
   "
  ^long [{:keys [status opts body headers error]}]
  (timbre/with-merged-config
    {:appenders {:println {:enabled? false}
                 :spit (appenders/spit-appender {:fname "log/process_result.log"})}}
    (debug (:thread opts) ":" (:rec-id opts) "=> S:" status " RC:" (get-RC body headers))
    (if (or error (nil? status) (nil? opts))
      (do
        (fatal "Failed, exception: error " error " status " status)
        (fatal (tread-details "!!!!" opts " - exited!!!"))
        ERROR)
      (_case (long status)
             http-errors/OK    (process-http-ok body headers status opts)

             http-errors/Unathorized  (process-http-unathorized body headers status opts)
  

             http-errors/Not-Found (process-http-not-found body headers status opts)

             http-errors/Bad-Request  (do
                                        (finalize-action-ERROR body headers status opts)
                                        OK)

             http-errors/Internal-Server-Error (process-http-internal-error body headers status opts)

             (do
               (timbre/error (tread-details (format "UnSuccess. Status %s. Server error in " status) opts "."))
               (finalize-action-ERROR body headers status opts)
               OK)))))