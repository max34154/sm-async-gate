(ns sm_async_api.task.sync_dispatcher_test
  {:clj-kondo/config  '{:linters {:unresolved-symbol
                                  {:exclude [responce-OK
                                             responce-BAD-REQ
                                             responce-INTERNAL-ERROR-GENERIC
                                             responce-INTERNAL-ERROR
                                             responce-NOT-ATHORIZED
                                             responce-TOO-MANY-THREADS
                                             responce-NO-SERVER-json
                                             responce-NO-SERVER-no-json
                                             responce-ERROR
                                             responce-OK-RC-CANT-HAVE
                                             responce-UNK-ATH-ERR
                                             responce-NO-MORE
                                             responce-NO-SERVER-NO-RC
                                             responce-UNK-ERROR
                                             responce-WRONG-CREDS
                                             with-fake-http]}
                                  :refer-all {:level :off}
                                  :lint-as {deftest clojure.core/def}}}}
  (:require
   [sm_async_api.task.sync_dispatcher :as sd]
   [sm_async_api.utils.http_fake :refer :all]
   [clojure.test :refer [testing  deftest  is use-fixtures]]
   [sm_async_api.config :as config]
   [sm_async_api.dal.user :as dal-u]
   [sm_async_api.dal.configure :refer [configure-database execute-script]]
   [sm_async_api.dal.globals :refer [request-action]]
   [sm_async_api.utils.reflector :refer [relector-set-responce
                                         reflector-start
                                         reflector-stop]]
   [sm-async-api.task.sm_fake_resp :refer :all]
   [clojure.walk :refer [keywordize-keys]]
   [sm_async_api.session :as session]
   [sm_async_api.utils.base64 :refer [string->b64]]
   [taoensso.timbre :as timbre
    :refer [;log  trace  info   warn  error  fatal  report
            ;logf tracef debugf infof warnf errorf fatalf reportf
            ;spy get-env
            debug  ]]
   [taoensso.timbre.appenders.core :as appenders]))

(defn sync-url  [test-action]
  (str (config/get-config :base-url) (test-action :service)
       "/" (test-action :subject)
       "/" (test-action :action)))


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
           :parameters parameters}
           ; :expire_at

   :service service})

(defn write-db [req]
  (let [result ((@request-action :insert) req);(dal/insert_action req)
        ]
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
  (doall (map  (get-action user action-template) (range qty))))

(defn  get-all-user-actions
  [qty action-template]
  (reduce #(concat %1 (get-actions %2 qty action-template)) {} test-users))


(defn read-action [action]
  (;dal/get_action
   (@request-action :get)
   {:route-params {:action_id (action :rec_id)}}))

(defn fix-read-config [t]
  (config/configure "test/")
  (println "Configured")
  ;(dal/open_db)
  ;(dal/check_db)
  (configure-database)
  (execute-script "TRUNCATE TABLE ASYNC.ATTACHMENT;TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;")
  (reflector-start)
  (with-redefs [dal-u/update-user (delay update-user)
                dal-u/get-user (delay get-user)
                dal-u/delete-user  (delay delete-user)]
    (reduce (fn [_ name] (session/new-session (string->b64 (str "Basic " name ":password")) name nil) 1) 0 (flatten test-users))
    (t)
    (reflector-stop)))


(defn fix-run-pushers [t]
  (timbre/merge-config!  {:appenders {:spit (appenders/spit-appender {:fname "log/dispatcher.log"})}})
  (timbre/set-level! :error)
  (debug "Start push manager")
  #_{:clj-kondo/ignore [:unused-binding]}
  (let [pushers (sd/pusher-manager-run)]
    (debug "Pushers configured")
    (sd/pusher-manager-do-all sd/pusher-manager-kick)
    (debug "Pushers are kicked off..")
    (t))
  (debug "Pushers is about to exit")
  (sd/pusher-manager-do-all sd/pusher-manager-kill))

(comment "
     Responce matrix 
   test                         !status         !ReturnCode !    -- Message --    ! Action ! Return code            
   ok-test                      !OK 200         ! SUCCESS 0 !      ANY            !   W    ! OK             
   CANT-HAVE-test               !OK 200         ! not 0     !      ANY            !   R    ! OK             
   NOT-ATHORIZED-UM-test        !Unathorized 401! WRONG_C -4! 'Not Authorized'    !   W    ! NOT-ATHORIZED  
   NOT-ATHORIZED-GM-test        !Unathorized 401! WRONG_C -4! 'Not Authorized'    !   W    ! NOT-ATHORIZED 
   too-many-threads-test        !Unathorized 401! WRONG_C -4! not 'Not Authorized'!   W/R  ! TOO-MANY-THREADS
   UNK-ATH-ERR-test             !Unathorized 401! not -4    !      ANY            !   W    ! ОК
   NO-MORE-test                 !Not-Found   404! NO_MORE  9!      ANY            !   W    ! ОК   
   NO-SERVER-json               !Not-Found   404! not 9     !      ANY            !   W/R    ! TOO-MANY-THREADS
   NO-SERVER-NO-RC              !Not-Found   404!not sm resp!      ANY            !   R     ! SERVER-NOT-AVAILABLE 
   NO-SERVER-no-json            !Not-Found   404!   -       !      -              !   R     ! SERVER-NOT-AVAILABLE    
   BAD-REQ-test                 !Bad-Request 400! ANY       !      ANY            !   W    ! ОК             
   WRONG-CREDS-test             !Not-Found   500! WRONG_C -4!      ANY            !   R    ! OK 
   INTERNAL-ERROR-GEN-test      !Not-Found   500!  -        !      ANY            !   W    ! OK 
   INTERNAL-ERROR-test          !Not-Found   500!  not -4   !      ANY            !   W    ! OK 
   UNK-ERROR-test               !ANY OTHER      !   ANY     !      ANY            !   W    ! OK
   ")

(defn post-actions-and-wait [actions wait-time]
  (debug "Posted actions:" (reduce  #(str %1 ":" %2) "" actions))
  ;(doseq [action actions] (print ":" (action :rec_id)))
  (debug "Sleep")
  (Thread/sleep wait-time)
  (debug "Awaken"))

(deftest ok-test
  (testing "Correct dispatcher working for dedicated mode "
    (relector-set-responce responce-OK)
    (let [actions  (get-all-user-actions 5 test-action)]
          ;(get-actions "max" 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (some? ((first (read-action action)) :res_status))))))));)


(deftest BAD-REQ-test
  (testing "Responce 400 "
    (relector-set-responce responce-BAD-REQ)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (= 400 ((first (read-action action)) :res_status))))))))


(deftest UNK-ERROR-test
  (testing "Responce 400 "
    (relector-set-responce responce-UNK-ERROR)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (= 10000 ((first (read-action action)) :res_status))))))))

(deftest INTERNAL-ERROR-test
  (testing "Responce 500 with json"
    (relector-set-responce responce-INTERNAL-ERROR)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (= 500 ((first (read-action action)) :res_status))))))))

(deftest INTERNAL-ERROR-GEN-test
  (testing "Responce 500 without json"
    (relector-set-responce responce-INTERNAL-ERROR-GENERIC)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (= 500 ((first (read-action action)) :res_status))))))))


(deftest CANT-HAVE-test
  (testing "Correct retry action "
    (relector-set-responce responce-OK-RC-CANT-HAVE)
    (let [actions  (get-all-user-actions 5 test-action)]
           ;(get-actions "max" 1 test-action)]
      (post-actions-and-wait actions 5000)
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

(deftest UNK-ATH-ERR-test
  (testing "Responce 401 witout SM responce "
    (relector-set-responce responce-UNK-ATH-ERR)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (some? ((first (read-action action)) :res_status))))))))

(deftest NO-MORE-test
  (testing "Responce 404 with SM responce  NO-MORE"
    (relector-set-responce responce-NO-MORE)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (some? ((first (read-action action)) :res_status))))))))

(deftest NO-SERVER-json-test
  (testing "Responce 404 with SM responce other then NO-MORE"
    (relector-set-responce responce-NO-SERVER-json)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (relector-set-responce responce-OK)
      (Thread/sleep 30000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (some? ((first (read-action action)) :res_status))))))))

(deftest NO-SERVER-NO-RC-test
  (testing "Responce 404 with not SM json"
    (relector-set-responce responce-NO-SERVER-NO-RC)
    (let [actions  ;(get-actions "max" 5 test-action)
          (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 20000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (< 1 ((first (read-action action)) :attempt))))))))

(deftest WRONG-CREDS-test
  (testing "Responce 500 with  SM responce  NOT-AUTHORIZED"
    (relector-set-responce responce-WRONG-CREDS)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 20000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (< 1 ((first (read-action action)) :attempt))))))))

(deftest NO-SERVER-no-json
  (testing "Responce 404 with content type other than json"
    (relector-set-responce responce-NO-SERVER-no-json)
    (let [actions   (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (< 1 ((first (read-action action)) :attempt))))))))

(deftest NOT-ATHORIZED-UM-test
  (testing "Responce 401 and SM WRONG CREDENTIALS for user mode thread"
    (relector-set-responce responce-NOT-ATHORIZED)
    (let [actions  (get-actions "max" 5 test-action)]
      (post-actions-and-wait actions 5000)
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (some? ((first (read-action action)) :res_status))))))))

(deftest NOT-ATHORIZED-GM-test
  (testing "Responce 401 and SM WRONG CREDENTIALS for global mode thread"
    (relector-set-responce responce-NOT-ATHORIZED)
    (let [actions  (get-actions "other1" 5 test-action)]
      (post-actions-and-wait actions 5000)
      (is (= 0   ((@sd/online-pushers  "1AEF-G::Global") :threads))))))



(defn t2 [& _]
  (if (> (rand) 0.5)  responce-OK responce-TOO-MANY-THREADS))

(deftest resp-too-many-threads-test
  (testing "Responce 401 and  SM WRONG CREDENTIALS and SM Message not 'Not Authorized' "
    (relector-set-responce t2)
    (let [actions  (get-all-user-actions 5 test-action)]
      (post-actions-and-wait actions 10000)
      (relector-set-responce responce-OK)
      (Thread/sleep 20000)
      (debug "Awaken")
      (doseq [action actions]
        (testing (format "Check if action %s processed "  (action :rec_id))
          (is (some? ((first (read-action action)) :res_status))))))))

(use-fixtures :each fix-run-pushers)
(use-fixtures :once fix-read-config)

