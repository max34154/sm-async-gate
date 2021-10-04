#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm-async-api.hook.hook
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [sm_async_api.dal.globals :refer [hook-action default-sm-user-name]]
            [sm_async_api.config :refer [get-uid]]
            [sm_async_api.utils.macro :refer [unixtime->timestamp tod]]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(def tag-border "%!%!%")

(def tag-border-pattern (re-pattern  "%!%!%"))
(def get-template (delay (:get-template @hook-action)))

(def queue-message (delay (:post @hook-action)))

(defn parse-body [body content-type]
  (when (str/includes? content-type "application/json") (json/parse-string body)))

(def ^:dynamic *parsed-body*)
(def ^:dynamic  *status*)
(def ^:dynamic *body*)
(def ^:dynamic *opts*)

(defn- replace-tags [to-replace]
 (str/join (map  (fn  [^String tag]
          (case tag
            "BODY" *body*
            "RC"  (get @*parsed-body* "ReturnCode")
            "MS"  (str "[\"" (str/join "\",\"" (get @*parsed-body* "Messages")) "\"]")
            "STATUS" *status*
            "REQ_ID" (:rec-id *opts*)
            "USER_NAME" (:user-name  *opts*)
            tag))
        (str/split to-replace tag-border-pattern))))

(defn create-message [hook-template opts ^Integer status body ^String content-type]
  (binding [*parsed-body* (delay (parse-body body content-type))
            *body* body
            *status* status
            *opts* opts]
    (@queue-message
     (spy
      {:url   (replace-tags (:url  hook-template))
       :headers  (replace-tags (:headers hook-template));(map (fn [[k,v]]  {k (replace-tags (v))}))
       :body (replace-tags (:body hook-template))
       :method (:method hook-template)
       :attempt (:max_retries hook-template)
       :next_run (unixtime->timestamp (tod))
       :retry_interval (:retry_interval hook-template)
       :id (:rec-id opts)}))))


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
     (try
       (create-message hook-template opts status body content-type)
       (catch Exception e  (error (ex-message e)
                                  (clojure.stacktrace/print-stack-trace e)
                                  "   \nMessage creation error."
                                  "   \nResponce: opts"  opts
                                  "   \n          status" status
                                  "   \n          status" content-type
                                  "   \n          body" body
                                  "   \nHook template:" hook-template))))))







