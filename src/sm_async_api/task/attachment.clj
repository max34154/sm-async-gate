#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.task.attachment
  (:require
   [sm_async_api.enum.sm :as sm]
   [clojure.string :as s]
   [cheshire.core :as json]
   [sm_async_api.utils.macro :refer [_case]]
   [sm_async_api.config :refer [get-executors-globals config]]
   [sm_async_api.dal.attachment :as dal-a]
   [sm_async_api.http_errors :as http-errors]
   [sm_async_api.enum.process_result :as pr]
   [org.httpkit.client :as http]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]])
  (:import [java.net URLEncoder]))

(def  fast-attachment-copy  "fast")

(def get-attachments-by-req-id (delay (dal-a/get-attachments-by-req-id-factory (:database @config))))

(def get-attachment-body-by-id (delay (dal-a/get-attachment-body-by-id-factory (:database @config))))

(def get-attachments-ids-by-req-id (delay (dal-a/get-attachments-ids-by-req-id-factory (:database @config))))

(def set-attachment-copy-mark (delay (dal-a/set-attachment-copy-mark-factory (:database @config))))


(def ^:const default-max-retry-waiting 15)

(def ^:const default-server-not-available-waiting 10)

(def ^:const default-max-retry-count 10)



(defn- post-attachment-factory [url, authorization]
  (fn [{:keys [content_type name body]}]
    @(http/post url
                {:headers {"Content-Type" content_type
                           "Content-Disposition" (str  "attachment;filename*=UTF-8''" (URLEncoder/encode ^String name "UTF-8"))
                           "Authorization" authorization}
                 :body body})))

(def ^:private message-not-found "$s:request not found. Request %s attachment %s. Responce: status %s, body %s")

(def ^:private message-srv-not-available  "$s:sm server not available. Request %s attachment %s. Responce: status %s, body %s")

(def ^:private message-susp-status-and-RC  "$s:suspections combination of status and RC. Request %s attachment %s. Responce: status %s, body %s")

(def ^:private message-not-authorized  "$s:not authorized. Request %s attachment %s. Responce: status %s, body %s" )
(def ^:private message-gen-error "$s:general error. Request %s attachment %s. Responce: status %s, body %s")

(defn- proccess-http-not-found [thread rec-id content-type att-id status body]
  (if  (s/includes? content-type "application/json")
    (_case (get (json/parse-string body) "ReturnCode")
           sm/RC_NO_MORE (do ; sm responce - has not requested item, possible reason deleted 
                                                               ; or not exitst request  
                           (timbre/errorf message-not-found thread rec-id att-id status body)
                           pr/NOT-ATHORIZED) ;; cancel other action with this attachments set
           nil (do ; ReturnCode is nil, most probably no SM
                 (timbre/errorf message-srv-not-available  thread rec-id att-id status body)
                 pr/SERVER-NOT-AVAILABLE)

           (do (timbre/warnf message-susp-status-and-RC  thread rec-id att-id status body)
               pr/TOO-MANY-THREADS )) ;cheat - this code reused to retry action
    (do ; content type not json
      (timbre/errorf message-srv-not-available  thread rec-id att-id status body)
      pr/SERVER-NOT-AVAILABLE)))

(defn- process-http-unathorized [thread rec-id  att-id  body]
  (let [jbody (json/parse-string body)]
    (if  (= (get jbody "ReturnCode") sm/RC_WRONG_CREDENTIALS)
      (if (and (some? (jbody "Messages"))
               (s/includes? ((jbody "Messages") 0) "Not Authorized"))
        (do
          (timbre/errorf message-not-authorized thread rec-id att-id http-errors/Unathorized body)
          pr/NOT-ATHORIZED)

        (do
          (timbre/warnf message-susp-status-and-RC  thread rec-id att-id http-errors/Unathorized body)
          pr/TOO-MANY-THREADS))
      (do
        (timbre/errorf message-not-authorized  thread rec-id att-id http-errors/Unathorized body)
        pr/NOT-ATHORIZED))))

(defn- process-http-delault [thread rec-id  att-id  body status]
  (timbre/errorf message-gen-error   thread rec-id att-id status body)
  pr/SERVER-NOT-AVAILABLE)

(defn process-http-responce
  "Process responce from SM. 
   Return code must by processed by push managers
   Responce matrix 
   !status         !ReturnCode !    -- Message --    ! Action ! Return code            
   !OK 200         ! SUCCESS 0 !      ANY            !   W    ! OK             
   !OK 200         ! not 0     !      ANY            !   R    ! OK             
   !Unathorized 401! WRONG_C -4! 'Not Authorized'    !   W    ! NOT-ATHORIZED  
   !Unathorized 401! WRONG_C -4! not 'Not Authorized'!   -    ! TOO-MANY-THREADS
   !Unathorized 401! not -4    !      ANY            !   W    ! NOT-ATHORIZED
   !Not-Found   404! NO_MORE  9!      ANY            !   W    ! pr/NOT-ATHORIZED             
   !Not-Found   404! not      9!      ANY            !   -    ! TOO-MANY-THREADS 
   !Not-Found   404! nil       !      ANY            !   -    ! SERVER-NOT-AVAILABLE 
   !ANY OTHER      ! ANY       !      ANY            !   W    ! OK
   "
  ^long [thread rec-id att-id {:keys [status  body headers]}]
  (_case (long status)

         http-errors/OK     (when-not (and (s/includes? (headers :content-type) "application/json")
                                           (= (get (json/parse-string body) "ReturnCode") sm/RC_SUCCESS))
                              (timbre/errorf "$s:copy error request %s attachment %s. Responce: status 200, body %s"
                                             thread, rec-id, att-id, body)
                              pr/OK)

         http-errors/Unathorized (process-http-unathorized thread rec-id  att-id  body)

         http-errors/Not-Found  (proccess-http-not-found thread rec-id (headers :content-type) att-id status body)

         (process-http-delault thread rec-id  att-id  body status)))

(defn- decode-resp [resp thread rec-id att-id]
  (let [{:keys [status  error]} resp]
    (if  error
      (do
        (fatalf "%s:failed, request %s attachment %sexception: error %s status %s"
                thread rec-id att-id error  status)
        {:att-id att-id :exit-code pr/ERROR :status status})
      {:att-id att-id
       :exit-code (process-http-responce thread rec-id att-id resp)
       :status status})))

(defn- retry-wait []
  (Thread/sleep  (* (or (get-executors-globals :max-retry-waiting) default-max-retry-waiting) (rand))))

(defn- server-not-available-waiting []
  (Thread/sleep  (or (get-executors-globals :server-not-available-waiting) default-server-not-available-waiting)))

(def  max-retry-count (delay (or (get-executors-globals :max-retry-count) default-max-retry-count)))


(def get-attachment-body-factory
  (delay (if (= (get-executors-globals :attachment-copy-mode) "fast") identity
             (fn [attachment]
               (assoc attachment :body (get-attachment-body-by-id (:att_id attachment)))))))

(defn copy [^String id ^String thread ^String url ^String authorization]
  (timbre/with-merged-config
    {:appenders {:println {:enabled? false}
                 :spit (appenders/spit-appender {:fname "log/attachment.log"})}}
    (let [post-attachment (post-attachment-factory  url, authorization)
          get-attachment-body (@get-attachment-body-factory)]
      (loop [attachments (get-attachments-by-req-id   id)
             retry @max-retry-count]
        (if (zero? retry)
          (errorf "%s:attachment copy session failed for request %s, max retry count exceeded."
                  thread id)
          (let [attachment (first attachments)
                att_id (:att_id attachment)
                resp (->  attachment
                          get-attachment-body
                          post-attachment
                          (decode-resp  thread id att_id))]

            (_case (:exit-code resp)
                   pr/OK  (do (set-attachment-copy-mark att_id (:status resp))
                              (recur (rest attachments) @max-retry-count))
                   pr/NOT-ATHORIZED (do (set-attachment-copy-mark att_id (:status resp))
                                        (recur (rest attachments) @max-retry-count))
                   pr/TOO-MANY-THREADS (do (retry-wait)
                                           (recur attachments (dec retry)))
                   pr/SERVER-NOT-AVAILABLE (do (server-not-available-waiting)
                                               (recur attachments (dec retry))))))))))


