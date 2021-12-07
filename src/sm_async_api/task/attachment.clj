#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.task.attachment
  (:require
   [sm_async_api.enum.sm :as sm]
   [clojure.string :as s]
   [cheshire.core :as json]
   [sm_async_api.utils.macro :refer [_case]]
   [sm_async_api.utils.sm_resp_decode :as sm-decode :refer [get-RC get-jbody]]
   [sm_async_api.config :as config :refer [get-executors-globals config get-keylist get-config]]
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

(def get-attachments-by-req-id (delay (dal-a/get-attachments-by-req-id-factory (:database @config) (get-executors-globals :attachment-copy-mode))))

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

(def ^:private message-not-found "%s:request not found. Request %s attachment %s. Responce: status %s, body %s")

(def ^:private message-srv-not-available  "%s:sm server not available. Request %s attachment %s. Responce: status %s, body %s")

(def ^:private message-susp-status-and-RC  "%s:suspections combination of status and RC. Request %s attachment %s. Responce: status %s, body %s")

(def ^:private message-not-authorized  "%s:not authorized. Request %s attachment %s. Responce: status %s, body %s")

(def ^:private message-gen-error "%s:general error. Request %s attachment %s. Responce: status %s, body %s")



(defn- proccess-http-not-found [thread rec-id headers att-id status body]

  (_case (get-RC body headers)
         sm/RC_NO_MORE (do ; sm responce - has not requested item, possible reason deleted 
                                                               ; or not exitst request  
                         (timbre/errorf message-not-found thread rec-id att-id status body)
                         pr/NOT-ATHORIZED) ;; cancel other action with this attachments set
         nil (do ; ReturnCode is nil, most probably no SM
               (timbre/errorf message-srv-not-available  thread rec-id att-id status body)
               pr/SERVER-NOT-AVAILABLE)

         (do (timbre/warnf message-susp-status-and-RC  thread rec-id att-id status body)
             pr/TOO-MANY-THREADS))) ;cheat - this code reused to retry action


(defn- process-http-unathorized [thread rec-id  att-id  body headers]
  (let [jbody (get-jbody body headers)]
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

(defn- process-http-default [thread rec-id  att-id  body status]
  (timbre/errorf message-gen-error   thread rec-id att-id status body)
  pr/SERVER-NOT-AVAILABLE)

(defn- process-http-OK [thread rec-id  att-id  body headers]
  (when-not (= (get-RC body headers) sm/RC_SUCCESS)
    (timbre/errorf "%s:copy error request %s attachment %s. Responce: status 200, body %s"
                   thread, rec-id, att-id, body))
  pr/OK)

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
   !Not-Found   404! NO_MORE  9!      ANY            !   W    ! NOT-ATHORIZED             
   !Not-Found   404! not      9!      ANY            !   -    ! TOO-MANY-THREADS 
   !Not-Found   404! nil       !      ANY            !   -    ! SERVER-NOT-AVAILABLE 
   !ANY OTHER      ! ANY       !      ANY            !   W    ! OK
   "
  ^long [thread rec-id att-id {:keys [status  body headers]}]

  (_case  status

          http-errors/OK     (process-http-OK thread rec-id  att-id  body headers)

          http-errors/Unathorized (process-http-unathorized thread rec-id  att-id  body headers)

          http-errors/Not-Found  (proccess-http-not-found thread rec-id headers  att-id status body)

          (process-http-default thread rec-id  att-id  body status)))

(defn- decode-resp [resp thread rec-id att-id]
  (let [{:keys [status  error]} resp]
    (if  error
      (do
        (fatalf "%s:failed, request %s attachment %sexception: error %s status %s"
                thread rec-id att-id error  status)
        {:att-id att-id :exit-code pr/ERROR :status 500})
      {:att-id att-id
       :body (:body resp)
       :content-type (-> resp :headers :content-type)
       :exit-code (process-http-responce thread rec-id att-id resp)
       :status status})))

(defn- retry-wait [ret-val]
  (Thread/sleep  (* (or (get-executors-globals :max-retry-waiting) default-max-retry-waiting) (rand)))
  ret-val)

(defn- server-not-available-waiting [ret-val]
  (Thread/sleep  (or (get-executors-globals :server-not-available-waiting) default-server-not-available-waiting))
  ret-val)

(def  max-retry-count (delay (or (get-executors-globals :max-retry-count) default-max-retry-count)))

(def fast-copy? (delay (= (get-executors-globals :attachment-copy-mode) "fast")))

(def get-attachment-body-factory
  (delay (if (= (get-executors-globals :attachment-copy-mode) "fast")
           identity
           (fn [attachment]
             (assoc attachment :body (get-attachment-body-by-id (:att_id attachment)))))))


(defmacro copied [resp att_id]
  `(do (@set-attachment-copy-mark ~att_id (:status ~resp))
       (reduced ~resp)))

(defn sender-factory  [thread req-id post-attachment]
  (fn [_ attachment]
    (timbre/with-merged-config
      {:appenders {:println {:enabled? false}
                   :spit (appenders/spit-appender {:fname "log/attachment.log"})}}
      (let [att_id (:att_id attachment)
            resp (decode-resp (post-attachment attachment) thread req-id att_id)]
        (_case (:exit-code resp)

               pr/ERROR  (copied resp att_id)

               pr/OK  (copied resp att_id)

               pr/NOT-ATHORIZED (copied resp att_id)

               pr/TOO-MANY-THREADS (retry-wait resp)

               pr/SERVER-NOT-AVAILABLE  (server-not-available-waiting resp))))))               



(defn- copy-report [result]
  (conj {:status (:status result)}
        (when-let [body  (try (json/parse-string (:body result) true)
                              (catch Exception e
                                (debugf "Json parcing error %s, json - %s "
                                        (ex-message e) (:body result))))]
          {:Messages (body :Messages)
           :ReturnCode (body :ReturnCode)
           :href (-> body :attachment :href);(when-let [attachment (body "attachment")] (attachment "href"))
           })))

(defn copy-attachment-factory  [sender]
  (fn [attachment]
      (debug "Ready to copy " (:name attachment) " attachment id  " (:att_id attachment))
      (conj
       {:name (:name attachment)
        :att_id (:att_id attachment)}
       (copy-report
        (reduce  sender nil (repeat @max-retry-count  attachment))))))


(defn- strip-body [body-json]
  (reduce #(if (map? (%2 1)) (reduced (%2 1)) {})
          {} (dissoc body-json :ReturnCode :Messages)))


(defn get-async-item-uid [body-json]
  (->>  (get-config :async-action-keys)
        (map #(get (strip-body body-json) %))
        (s/join "/")))


(defn- get-other-item-uid [subject service body-json]
  (if (nil? subject)
    (->> (@config/config :collections-keylist)
         ((keyword service))
         (map #(get (strip-body body-json) %))
         (s/join "/"))
    subject))



(defn build-attachment-url [{:keys [subject service mode]} body-json]
  (when (some? body-json)
    (if (= mode :async-mode)
      (str (get-config :async-action-url) "/" (get-async-item-uid  body-json) "/attachments")
      (str (get-config :base-url)  service "/" (get-other-item-uid subject service body-json) "/attachments"))))


(defn copy
  ([opts body]
   (timbre/with-merged-config
     {:appenders {:println {:enabled? false}
                  :spit (appenders/spit-appender {:fname "log/attachment.log"})}}
     (let [url  (build-attachment-url opts (json/parse-string body))]
       (if (some? url)
         (let [{:keys [rec-id thread headers]}  opts
               authorization (get headers "Authorization")
               get-attachment-body @get-attachment-body-factory
               copy-attachment  (copy-attachment-factory
                                 (sender-factory thread rec-id
                                                 (post-attachment-factory  url, authorization)))]
           ;(debug "Attachment list for " rec-id ": " (reduce #(str %1 " :: " (:name %2)) "" (@get-attachments-by-req-id   rec-id)))
           ;(debug "Copy url " url " for "  rec-id)
           ;(debug "Fast copy option is " @fast-copy? " for " rec-id)
           ;(debug "opts" opts)
           ;(debug "body" body)
           (if @fast-copy?
             (doall (map copy-attachment (@get-attachments-by-req-id   rec-id)))
             (doall (map (comp copy-attachment get-attachment-body)  (@get-attachments-by-req-id   rec-id)))))
         (errorf "Build attachment copy url for opts %s and body %s " opts body))))))
