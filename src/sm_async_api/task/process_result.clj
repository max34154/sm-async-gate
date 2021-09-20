#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.task.process_result
  (:require
   [sm_async_api.enum.sm :as sm]
   [clojure.string :as s]
   [cheshire.core :as json]
  ; [sm_async_api.config :as config]
   [sm_async_api.utils.macro :refer [_case]]
   [sm_async_api.http_errors :as http-errors]
   [sm_async_api.task.writers :as tw]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]))

(def ^:const OK  0)

(def  ^:const TOO-MANY-THREADS 1)

(def  ^:const SERVER-NOT-AVAILABLE 2)

(def  ^:const NOT-ATHORIZED 3)

(def  ^:const ERROR 4)





(defmacro ^:private tread-details
  ([opts] `(format "Thread:%s,Mode:%s,User:%s,RecID:%s,Url:%s"
                   (:thread ~opts) (:mode ~opts)
                   (:user-name ~opts) (:rec-id ~opts) (:url ~opts)))

  ([prev opts  post]
   `(str (if (nil? ~prev) "" ~prev)
         (tread-details ~opts)
         (if (nil? ~post) "" ~post))))

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
  [{:keys [status opts body headers error]}]

  (if (or error (nil? status) (nil? opts))
    (do
      (fatal "Failed, exception: error " error " status " status)
      (fatal (tread-details "!!!!" opts " - exited!!!"))
      ERROR)
    (timbre/with-merged-config
      {:appenders {:println {:enabled? false}
                   :spit (appenders/spit-appender {:fname "log/process_result.log"})}}
      (debug (:thread opts) ":" (:rec-id opts) "=> S:" status " RC:" (get (json/parse-string body) "ReturnCode"))
      (_case status


             http-errors/OK    (do ; - just in case of http success and error in error code 
                                 (let [writed-res
                                       (if (and (s/includes? (headers :content-type) "application/json")
                                                (= (get (json/parse-string body) "ReturnCode") sm/RC_SUCCESS))
                                         (@tw/result-writer (opts :rec-id) body status)
                                         (@tw/action-rescheduler  (opts :rec-id) body status))]
                                   (debug (:thread opts) ":" (:rec-id opts) " write result " writed-res)
                                   (when (string? writed-res)
                                     (timbre/error (tread-details  "Write error in status http-errors/OK"
                                                            opts
                                                            (str "Responce body " body)))))
                                 OK)

             http-errors/Unathorized (let [jbody (json/parse-string body)]
                                       (if  (= (get jbody "ReturnCode") sm/RC_WRONG_CREDENTIALS)
                                         (if (and (some? (jbody "Messages"))
                                                  (s/includes? ((jbody "Messages") 0) "Not Authorized"))
                                           (do
                                             (timbre/error "Not Authorized. Write the answer. Body:"  body)
                                             (when (string? (@tw/result-writer (opts :rec-id) body status))
                                               (timbre/error (tread-details "Write error in status http-errors/Unathorized"
                                                                            opts
                                                                            (str "Responce body " body))))
                                             NOT-ATHORIZED)

                                           (do
                                             (warn "Two many threads." (tread-details   opts))
                                             TOO-MANY-THREADS))
                                         (do
                                           (when (string? (@tw/result-writer (opts :rec-id) body status))
                                             (timbre/error (tread-details "Write error in status http-errors/Unathorized"
                                                                          opts
                                                                          (str "Responce body " body))))
                                           (timbre/error "Unkown athorization error:"  body)
                                           OK)))

             http-errors/Not-Found  (if  (s/includes? (headers :content-type) "application/json")
                                      (_case (get (json/parse-string body) "ReturnCode")
                                             sm/RC_NO_MORE (do ; sm responce - has not requested item 
                                                             (when (string? (@tw/result-writer (opts :rec-id) body status))
                                                               (timbre/error (tread-details "Write error in status http-errors/Not-Found"
                                                                                            opts
                                                                                            (str "Responce body " body))))
                                                             (timbre/error "Attempt to use incorrect service name:" body)
                                                             OK)
                                             nil (do ; ReturnCode is nil, most probably no SM
                                                   (timbre/error (tread-details "SM not available - " opts "."))
                                                   SERVER-NOT-AVAILABLE)
                                             
                                             (do (warn (tread-details
                                                        (format "Suspections combination of status 404 and ReturnCode %s"
                                                                (get (json/parse-string body) "ReturnCode"))
                                                        opts "."))
                                                 TOO-MANY-THREADS ;cheat - this code reused to retry action 
                                                 ))

                                      (do ; content type not json
                                        (timbre/error (tread-details "SM not available - " opts "."))
                                        SERVER-NOT-AVAILABLE))

             http-errors/Bad-Request  (do
                                        (when (string?  (@tw/result-writer (opts :rec-id) body status))
                                          (timbre/error (tread-details "Write error in status http-errors/Bad-Request"
                                                                       opts
                                                                       (str "Responce body " body))))
                                        OK)

             http-errors/Internal-Server-Error (do
                                                 (when (string?
                                                        (if (and (s/includes? (headers :content-type) "application/json")
                                                                 (= (get (json/parse-string body) "ReturnCode") sm/RC_WRONG_CREDENTIALS))
                                                          (@tw/action-rescheduler  (opts :rec-id) body status)
                                                          (@tw/result-writer (opts :rec-id) body status)))
                                                   (timbre/error (tread-details "Write error in status http-errors/Internal-Server-Error"
                                                                                opts
                                                                                (str "Responce body " body))))
                                                 OK)

             (do
               (timbre/error (tread-details (format "UnSuccess. Status %s. Server error in " status) opts "."))
               (when (nil? (@tw/result-writer (opts :rec-id) body status))
                 (timbre/error (tread-details "Write error in unknown status"
                                              opts
                                              (str "Responce body " body))))
               OK)))))