
(ns sm_async_api.dal.request
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [sm_async_api.validate]
            [sm_async_api.config :as config]
            [sm_async_api.dal.globals :as g :refer [db]]
            [sm_async_api.utils.macro :refer [tod unixtime->timestamp]]
            [taoensso.timbre :as timbre
             :refer [;log  trace  debug  info  warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf
                     ;spy get-env
                     error ]]
            [taoensso.timbre.appenders.core :as appenders])
  (:import [java.sql SQLException]))

(defn wrap-insert [action req]
  (timbre/with-merged-config
    {:appenders {:spit (appenders/spit-appender {:fname "log/request.log"})}}
    (try (action req)
         (catch SQLException e (error (ex-message e))
                {:err "Can't insert action into DB."})
         (catch  AssertionError e (error (ex-message e))
                 {:err "Incorrect action options" :status 422}))))

(defn insert-action-factory [{:keys [db-schema]}]
  (let [request (keyword (str db-schema ".REQUEST"))]
    (fn [req] {:pre [(s/valid? :sm_async_api.validate/post-action-request req)]}
      (let [{:keys [rec_id route-params user_name  body service]} req
            {:keys [status schedule_name execution_mode execution_retries retry_interval parameters expire_at]} body]
        (jdbc/insert! @db request
                     
                      {:req_id rec_id
                       :user_name user_name
                       :status (or status "N")
                       :schedule_name schedule_name
                       :execution_mode execution_mode
                       :execution_retries (if (number? execution_retries) execution_retries 1)
                       :retry_interval (if (number? retry_interval)  retry_interval g/_default_retry_interval)
                       :action (route-params :action_id)
                       :parameters parameters
                       :expire_at expire_at ;(if (nil? expire_at) "NULL" expire_at)
                       :service (str service)
                       :subject (route-params :name)})))))


(defn cancel-action-factory [{:keys [db-schema]}]
  (let [request  (str db-schema ".REQUEST")]
    (fn [req]
      (jdbc/execute! @db [(str "UPDATE " request " set status='C' WHERE REQ_ID=? and user_name=?")
                          (-> req :route-params :action_id)
                          (req :user_name)]))))



(defn run-action-factory [{:keys [db-schema]}]
  (let [request  (str db-schema ".REQUEST")]
    (fn  [req]
      (jdbc/execute! @db [(str "UPDATE " request " set status='N' WHERE  status='W'and REQ_ID=? and user_name=?")
                          (-> req :route-params :action_id) (req :user_name)]))))


(defn get-result-factory [{:keys [db-schema]}]
  (let [result  (str db-schema ".RESPONCE")]
    (fn [req]
      (jdbc/query @db
                  [(str "SELECT RES_REQ_ID, STRINGDECODE(result) as result, close_time FROM " result "WHERE RES_REQ_ID=?")
                   (-> req :route-params :action_id)]))))



(defmulti ^:private full-action-field-list (fn [db-config] (:db-type  db-config)))

(defmethod full-action-field-list :default [db-config]
  (throw (IllegalArgumentException.
          (str "Unsupported database type " (:db-type  db-config) "."))))






(defn get-action-factory 
  "Get action and result by id in request structure {:route-params {:action_id id}}"
  [db-config]
  (let [db-schema (:db-schema db-config)
        sql (str "SELECT " (g/full-action-field-list db-config) 
                 " FROM " db-schema ".REQUEST"
                 " LEFT JOIN " db-schema ".RESPONCE" 
                 " ON REQ_ID=RES_REQ_ID "
                 " WHERE REQ_ID=?")]
    (fn   [req]
      (jdbc/query @db
                  [sql (-> req :route-params :action_id)]))))


(defn cleanup-excuted-factory
  "Remove all executed or expired request after specified delay"
  [{:keys [^String db-schema]} ]
  (fn [ delay ] {:pre [(pos-int? delay)]}
    (let [offset (unixtime->timestamp (- (tod) delay))]
    (jdbc/execute!  @db
                    [(str "DELETE FROM " db-schema ".REQUEST  "
                         "WHERE STATUS='E' and  next_run < ? " offset) ]
                    
    (jdbc/execute!  @db  [(str "DELET FROM " db-schema ".REQUEST  "
                              "WHERE req_id in "
                              "(SELECT req_id FROM " db-schema ".REQUEST"
                              " LEFT JOIN " db-schema ".RESPONCE ON REQ_ID=RES_REQ_ID "
                              " WHERE finished='f' and close_time < ? " ) 
                          (unixtime->timestamp (+ (tod) delay))] ) ))))
;;
;;  For tests and not massive usage 
;;  due to execute factory faction every run 
;;
;;

(defn insert-action
  "Attention: not for massive usage due to rebuilding insert action"
  [req]
  (wrap-insert (insert-action-factory (:database @config/config)) req))

(defn cancel-action
  "Attention: not for massive usage due to rebuilding cancel action"
  [req]
  ((cancel-action-factory (:database @config/config)) req))

(defn run-action
  "Attention: not for massive usage due to rebuilding run action"
  [req]
  ((run-action-factory (:database @config/config)) req))

(defn get-result
  "Attention: not for massive usage due to rebuilding get-result"
  [req]
  ((get-result-factory (:database @config/config)) req))

(defn get-action
  "Attention: not for massive usage due to rebuilding get-result"
  [req]
  ((get-action-factory (:database @config/config)) req))

(defn configure [ db-config]
  {:insert (insert-action-factory db-config)
   :cancel (cancel-action-factory db-config)
   :run  (run-action-factory db-config)
   :get-result (get-result-factory db-config)
   :get (get-action-factory db-config)
   :cleanup (cleanup-excuted-factory db-config)})




