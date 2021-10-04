#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.task.process_result
  (:require
   [sm_async_api.enum.sm :as sm]
   [clojure.string :as s]
   [cheshire.core :as json]
   [sm_async_api.utils.macro :refer [_case]]
   [sm_async_api.http_errors :as http-errors]
   #_{:clj-kondo/ignore [:refer-all]}
   [sm_async_api.enum.process_result :refer :all]
   [sm_async_api.task.writers :as tw]
   [sm_async_api.task.attachment :as attachments]
   [sm_async_api.config :as config]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]))

#_(def ^:const ^Integer OK  0)

#_(def  ^:const ^Integer  TOO-MANY-THREADS 1)

#_(def  ^:const ^Integer  SERVER-NOT-AVAILABLE 2)

#_(def  ^:const ^Integer  NOT-ATHORIZED 3)

#_(def  ^:const ^Integer ERROR 4)





(defmacro ^:private tread-details
  ([opts] `(format "Thread:%s,Mode:%s,User:%s,RecID:%s,Url:%s"
                   (:thread ~opts) (:mode ~opts)
                   (:user-name ~opts) (:rec-id ~opts) (:url ~opts)))

  ([prev opts  post]
   `(str (if (nil? ~prev) "" ~prev)
         (tread-details ~opts)
         (if (nil? ~post) "" ~post))))

(defn get-async-item-uid [body-json]
  (->>  @config/config
        :async-action-keys
        (map #(get body-json %))
        (s/join "/")))

(defn get-other-item-uid [subject service body-json]
  (if (nil? subject)
    (->> @config/config
         :collection
         ((keyword service)
          :keys)
         (map #(get body-json %))
         (s/join "/"))
    subject))

(defn build-attachment-url [subject service mode body-json]
  (if (= mode :async-mode)
    (str (config/get-config :async-action-url) "/" (get-async-item-uid  body-json) "/attachment")
    (str (config/get-config :base-url) "/" (get-other-item-uid subject service body-json) "/attachment")))

(defn log-error [err opts body]
  (when (string? err) (timbre/error
                       (tread-details  "Write error in status http-errors/OK"
                                       opts
                                       (str "Responce body " body)))))

(defn copy-attachments [err {:keys [rec-id mode headers thread subject service]} body-json]
  (if (string? err)  err
      (attachments/copy rec-id thread
                        (build-attachment-url subject service mode body-json)
                        (get headers "Authorization"))))

(defn process-http-ok [body headers status opts]
  (let [body-json (when (s/includes? (headers :content-type) "application/json")
                    (json/parse-string body))]
    (if (= (get body-json "ReturnCode") sm/RC_SUCCESS)
      (log-error
       (copy-attachments
        (@tw/result-writer (opts :rec-id) body status) opts body-json) opts body)
      (log-error (@tw/action-rescheduler  (opts :rec-id) body status) opts body)))
  OK)

(defmacro get-jbody [body headers]
  `(when (s/includes? (~headers :content-type) "application/json") (json/parse-string ~body)))

(defmacro get-RC [body headers]
  `(get (get-jbody ~body ~headers) "ReturnCode" ))

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
             #_(do ; - just in case of http success and error in error code 
                 (let [body-json (when (s/includes? (headers :content-type) "application/json")
                                   (json/parse-string body))
                       writed-res
                       (if (= (get body-json "ReturnCode") sm/RC_SUCCESS)
                         (@tw/result-writer (opts :rec-id) body status)
                         (@tw/action-rescheduler  (opts :rec-id) body status))]
                   (debug (:thread opts) ":" (:rec-id opts) " write result " writed-res)
                   (when (string? writed-res)
                     (timbre/error (tread-details  "Write error in status http-errors/OK"
                                                   opts
                                                   (str "Responce body " body)))))
                 OK)

             http-errors/Unathorized (let [jbody 
                                           (when (s/includes? (headers :content-type) "application/json") (json/parse-string body))]
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