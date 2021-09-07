(ns sm_async_api.task.sync_dispatcher_test
  {:clj-kondo/config  '{:linters {:unresolved-symbol
                                  {:exclude [responce-OK
                                             responce-BAD-REQ
                                             responce-INTERNAL-ERROR-GENERIC
                                             responce-INTERNAL-ERROR
                                             responce-NOT-ATHORIZED
                                             responce-TOO-MANY-TREADS
                                             responce-NO-SERVER-json
                                             responce-NO-SERVER-no-json
                                             responce-ERROR
                                             responce-OK-RC-CANT-HAVE
                                             with-fake-http]}
                                  :refer-all {:level :off}
                                  :lint-as {deftest clojure.core/def}}}}
  (:require
   [sm_async_api.task.sync_dispatcher :as sd]
   [sm_async_api.utils.http_fake :refer :all]
   [clojure.test :refer [testing  deftest  is use-fixtures]]
   [sm_async_api.config :as config]
   [sm_async_api.dal :as dal]
   [sm_async_api.utils.reflector :refer [relector-set-responce
                                         reflector-restart
                                         reflector-start
                                         reflector-stop]]
   [sm-async-api.task.sm_fake_resp :refer :all]
   [clojure.walk :refer [keywordize-keys]]
   [sm_async_api.session :as session]
   [sm_async_api.utils.base64 :refer [string->b64]]
   [taoensso.timbre :as timbre
    :refer [;log  trace    warn  error  fatal  report
            ;logf tracef debugf infof warnf errorf fatalf reportf
            ;spy get-env
            debug  info]]
   [taoensso.timbre.appenders.core :as appenders]))

(defn sync-url  [test-action]
  (str (config/get-config :base-url) (test-action :service)
       "/" (test-action :subject)
       "/" (test-action :action)))

;(config/get-config :async-action-url)

(defn keywordize-header [{:keys [headers] :as resp}]
  (assoc resp :headers (keywordize-keys headers)))


(defn update-user [_] [1])

(defn  get-user [user-name]  {:name user-name :val {:password (string->b64 (str "Basic " user-name ":password"))
                                                    :name user-name
                                                    :expire_at nil}})
(defn delete-user [_] [1])

(def test_db
  {:classname   "org.h2.Driver"
   :subprotocol "h2:mem"
   :subname     "demo;DB_CLOSE_DELAY=-1"
   :user        "sa"
   :password    ""})

(def test-users ["max", "test", ["dima","vs"], ["other1", "other2"]])

(def test-action  {:user_name "test"
                   :service "test-service"
                   :subject "test-subject"
                   :execution_mode "IS"
                   :action "test-action"
                   :attempt 1
                   :execution_retries 2
                   :retry_interval 10
                   :rec_id "test_id"
                   :parameters "{ \"item-name\": {\"field_1\":\"val1\"}}"
                   :schedule_name "test schedule"})

(defn action->req [{:keys [service execution_retries execution_mode
                           ;rec_id ;attempt
                           action schedule_name retry_interval
                           user_name parameters subject]}]
  {:rec_id (config/get-uid)
   :route-params  {:name subject
                   :action_id action}
   :user_name user_name
   :body  {:status "N"
           :schedule_name schedule_name
           :execution_mode execution_mode
           :execution_retries execution_retries
           :retry_interval retry_interval
           :parameters parameters
           ; :expire_at
           }
   :service service})

(defn write-db [req]
  (let [result (dal/insert_action req)]
    (if  (vector?  result)
      {:rec_id (req :rec_id)
       :status 200}
      {:rec_id (req :rec_id)
       :status (:status result)
       :err (str result)})))


(defn get-action [user action-template]
  (if (vector?  user)
    (let [lngth (count user)]
      (fn  [pos]
        (-> action-template
            (assoc :user_name (user (rand-int lngth)))
            (assoc :parameters (str "{\"action_number\":" pos
                                    ", \"item-name\": {\"field_1\":\"val1\"}}"))
            action->req
            write-db)))
    (fn [pos]
      (-> action-template
          (assoc :user_name   user)
          (assoc :parameters (str "{\"action_number\":" pos
                                  ", \"item-name\": {\"field_1\":\"val1\"}}"))
          action->req
          write-db))))

(defn  get-actions
  [user qty action-template]
  (map  (get-action user action-template) (range qty)))

(defn  get-all-user-actions
  [qty action-template]
  (reduce #(concat %1 (get-actions %2 qty action-template)) {} test-users))


(defn read-action [action]
  (dal/get_action {:route-params {:action_id (action :rec_id)}}))


(defn fix-read-config [t]
  (config/configure "test/")
  (println "Configured")
  (dal/open_db)
  (dal/check_db)
  (dal/execute_script "TRUNCATE TABLE ASYNC.ATTACHMENT;TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;")
  (reflector-start)
  (with-redefs [sm_async_api.dal/update-user update-user
                sm_async_api.dal/get-user get-user
                sm_async_api.dal/delete-user delete-user]
    (reduce (fn [_ name] (session/new-session (string->b64 (str "Basic " name ":password")) name nil) 1) 0 (flatten test-users))
    (t)
    (reflector-stop)))


(defn fix-test-data  [t]
  (t))
  ;(dal/execute_script "TRUNCATE TABLE ASYNC.ATTACHMENT;TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;"))

(defn run-pushers [t]
  (timbre/merge-config!  {:appenders {:spit (appenders/spit-appender {:fname "log/dispatcher.log"})}})
  (timbre/set-level! :info)
  (info "Start push manager")
  #_{:clj-kondo/ignore [:unused-binding]}
  (let [pushers (sd/pusher-manager-run)]
    (println "Pushers configured")
    (sd/pusher-manager-do-all sd/pusher-manager-kick)
    (info "Pushers are kicked off..")
    (t))
  (println "Pushers is about to exit")
  (sd/pusher-manager-do-all sd/pusher-manager-kill))

#_(deftest write-db-test
    (testing "Prelimenary check db writing/reading"
      (let [actions  (get-actions ["dima","vs"] 5 test-action)]
        (doseq [action actions]
          (testing (format "Check if action %s exists "  (action :rec_id))
            (is (= (action :rec_id) ((first (read-action action)) :req_id))))))))

(deftest resp-ok-test
    (testing "Correct dispatcher working for dedicated mode "
      (relector-set-responce responce-OK)
    ;(with-fake-http [(sync-url test-action) (keywordize-header responce-OK)]
      (let [actions  (get-all-user-actions 5 test-action)]
          ;(get-actions "max" 5 test-action)]
        (println "Posted actions:")
        (doseq [action actions] (println " -" (action :rec_id)))
        (debug "Sleep")
        (Thread/sleep 5000)
        (debug "Awaken")
        (doseq [action actions]
          (testing (format "Check if action %s processed "  (action :rec_id))
            (is (some? ((first (read-action action)) :res_status))))))));)

(deftest resp-CANT-HAVE-test
  (testing "Correct retry action "
    (relector-set-responce responce-OK-RC-CANT-HAVE)
    (let [actions  (get-all-user-actions 5 test-action)]
          ;(get-actions "max" 1 test-action)]
      (println "Posted actions:")
      (doseq [action actions] (println " -" (action :rec_id)))
      (debug "Sleep")
      (Thread/sleep 5000)
      (debug "Awaken")
      (testing "Schedule for retry"
        (doseq [action actions]
          (testing (format "Check if action %s processed "  (action :rec_id))
            (is (= 2 ((first (read-action action)) :attempt))))))
      (debug "Wait attempt 2 ")
      (Thread/sleep 30000)
      (testing "Retry and cancel"
        (doseq [action actions]
          (testing (format "Check if action %s processed "  (action :rec_id))
            (is (= 3 ((first (read-action action)) :attempt)))))))))





(use-fixtures :each fix-test-data run-pushers)
(use-fixtures :once fix-read-config)

