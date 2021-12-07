#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm-async-api.hook.hook
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [sm_async_api.dal.globals :refer [hook-action default-sm-user-name]]
            [sm_async_api.config :refer [get-uid]]
            [sm_async_api.task.attachment :as attachment]
            [sm_async_api.utils.macro :refer [unixtime->timestamp tod]]
            [clojure.core.async :as a]
            [taoensso.timbre.appenders.core :as appenders]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(def tag-border "%!%!%")

(def tag-border-pattern (re-pattern  "%!%!%"))
(def get-template (delay (:get-template @hook-action)))

(def queue-message (delay (:post @hook-action)))

(defn parse-body [body content-type]
  ;(debug "content-type:" content-type " -> body" body "" )
  (when (str/includes? content-type "application/json") (json/parse-string body)))

(def ^:dynamic *parsed-body*)
(def ^:dynamic *status*)
(def ^:dynamic *body*)
(def ^:dynamic *opts*)
(def ^:dynamic *copy-report*)



(defmacro copy-report-required? [template]
  `(str/includes? (:body ~template) (str tag-border-pattern "COPY_REPORT" tag-border-pattern)))

(defn- replace-tags [to-replace]
  (str/join (map  (fn  [^String tag]
                    (case tag
                      "BODY" *body*
                      "RC"  (get @*parsed-body* "ReturnCode")
                      "MS"  (str "[\"" (str/join "\",\"" (get @*parsed-body* "Messages")) "\"]")
                      "STATUS" *status*
                      "REQ_ID" (:rec-id *opts*)
                      "USER_NAME" (:user-name  *opts*)
                      "COPY_REPORT" (json/generate-string *copy-report*)
                      tag))
                  (str/split to-replace tag-border-pattern))))

(defn create-message [hook-template opts ^Integer status body ^String content-type]
  (binding [*parsed-body* (delay (parse-body body content-type))
            *body* body
            *status* status
            *opts* opts]
    (@queue-message
       (spy :debug
            {:url   (replace-tags (:url  hook-template))
             :headers  (replace-tags (:headers hook-template))
             :body (replace-tags (:body hook-template))
             :method (:method hook-template)
             :attempt (:max_retries hook-template)
             ;:next_run (unixtime->timestamp (tod))
             :user_name (:user-name opts)
             :retry_interval (:retry_interval hook-template)
             :id (:rec-id opts)}))
    #_(@queue-message
     [(replace-tags (:url  hook-template))
      (replace-tags (:headers hook-template))
      (replace-tags (:body hook-template))
      (:method hook-template)
      (:max_retries hook-template)
      (:user-name opts)
      (:retry_interval hook-template)
      (:rec-id opts)])))


(defn- run-copy [opts body]
  (debug "Run copy for " (:rec-id opts))
  (try (if (timbre/may-log? :debug)
         (timbre/with-merged-config
           {:appenders {:println {:enabled? false}
                        :spit (appenders/spit-appender {:fname "log/attachment.log"})}}
           (let [copy-protocol (attachment/copy opts body)]
             (debug "Copy protocol for " (:rec-id opts) " " (json/generate-string copy-protocol))
             copy-protocol))
         (attachment/copy opts body))
       (catch Exception e  (errorf "Attachments copy for %s error %s "
                                   (:rec-id opts) (ex-message e)))))


(defn preview-message [req hook-template]
  (let [params  (req :params)]
    (binding [*parsed-body* (delay (get params "responce"))
              *body* (json/generate-string (get params "responce"))
              *status* (get params "responce_status")
              *opts* {:rec-id (get-uid)
                      :user-name (:user_name req)
                      :tag (-> req :route-params :action_id)}]
      ;(debug "Req " req)
      {:url   (replace-tags (:url  hook-template))
       :headers  (replace-tags (:headers hook-template));(map (fn [[k,v]]  {k (replace-tags (v))}))
       :body (replace-tags (:body hook-template))
       :method (:method hook-template)
       :attempt (:max_retries hook-template)
       :next_run (unixtime->timestamp (tod))
       :user_name (:user_name req)
       :retry_interval (:retry_interval hook-template)
       :id (:rec-id *opts*)})))



#_(defn test->req [test-req]
    (spy :debug {:opts {:rec-id (get-uid)
                        :user-name (:user_name test-req)
                        :tag (-> test-req :route-params :action_id)}
                 :status (-> test-req :params (get "responce_status"))
                 :body (slurp (:body test-req))
                 :content-type (:content-type test-req)}))

(defn post-message
  "Create message and post it to message queue
   One parameter version for request recived via http,
   the other for internal usage in task processing cycle.
   "
  ([req]
   (post-message  {:rec-id (get-uid)
                   :user-name default-sm-user-name
                   :tag (-> req :body :tag)}
                  (-> req :body :status)
                  (-> req :body :parameters)
                  "application/json"))

  ([opts status body content-type]
   (when-let [hook-template (first (@get-template opts))]
     (binding [*copy-report* []]
       (try
         (create-message hook-template opts status body content-type)
         (catch Exception e  (error (ex-message e)
                                  ;(clojure.stacktrace/print-stack-trace e)
                                    "   \nMessage creation error."
                                    "   \nResponce: opts"  opts
                                    "   \n          status" status
                                    "   \n          status" content-type
                                    "   \n          body" body
                                    "   \nHook template:" hook-template)))))))

(defn test-run-message [req]
  (post-message {:rec-id (get-uid)
                 :user-name (:user_name req)
                 :tag (-> req :route-params :action_id)}
                (-> req :params (get "responce_status"))
                (json/generate-string (-> req :params (get "responce")))
                (:content-type req)))


(defn post-and-copy
  "Post copy attachment and post message.
   If the message template requires information about copied attachments, 
   the attachents are copied before posting message.
   !! It's assumed that action was completed successfully"
  [opts status body headers]
  (let [hook-template (first (@get-template opts))
        copy-first? (and (some? hook-template) (copy-report-required? hook-template))
        content-type (:content-type headers)]
    (debugf "Post and copy paramaters for %s: hook-template %s, copy-first? %s" (:rec-id opts) hook-template copy-first?)
    (when  (some? hook-template)
      (binding [*copy-report* (when copy-first? (run-copy opts  body))]
        (try
          (create-message hook-template opts status body content-type)
          (catch Exception e  (error (ex-message e)
                                  ;(clojure.stacktrace/print-stack-trace e)
                                     "   \nMessage creation error."
                                     "   \nResponce: opts"  opts
                                     "   \n          status" status
                                     "   \n          status" content-type
                                     "   \n          body" body
                                     "   \nHook template:" hook-template)))))
    (when-not copy-first? (a/thread (run-copy opts body)))))




