(ns sm-async-api.task.sync_pusher_test
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
                                             with-fake-http]}
                                  :refer-all {:level :off}
                                  :lint-as {deftest clojure.core/def}}}}
  #_{:clj-kondo/ignore [:refer-all]}
  (:require
   [sm_async_api.task.sync_pusher :as sp]
   [sm_async_api.utils.http_fake :refer :all]
   [clojure.test :refer [deftest testing use-fixtures is are]]
   [sm_async_api.task.writers]
   ;[sm_async_api.utils.base64 :as b64]
   [sm_async_api.config :as config]
   [sm_async_api.enum.task_result :as tr]
   [sm_async_api.session :as session]
   [sm_async_api.dal :as dal]
   [clojure.walk :refer [keywordize-keys]]
   [cheshire.core :as json]
   [sm-async-api.task.sm_fake_resp :refer :all]
   [taoensso.timbre :as timbre :refer [spy]]))

;(timbre/merge-config!  {:appenders {:spit (appenders/spit-appender {:fname "log/pusher.log"})}})
(timbre/merge-config! {:appenders  {:println {:enabled? true}}})

(def test-action  {:user_name "test"
                   :service "test-service"
                   :subject "test-subject"
                   :execution_mode "IS"
                   :action "test-action"
                   :attempt 10
                   :execution_retries 50
                   :retry_interval 20
                   :req_id "test_id"
                   :parameters "{ \"item-name\": {\"field_1\":\"val1\"}}"
                   :schedule_name "test schedule"})

(defn sync-url  [test-action]
  (str (config/get-config :base-url) (test-action :service)
       "/" (test-action :subject)
       "/" (test-action :action)))

(defn keywordize-header [{:keys [headers] :as resp}]
  (assoc resp :headers (keywordize-keys headers)))

(def authorization "password")

;(def  ^:private global-credentials (str "Base" (b64/string->b64 (str (config/module-login 0) ":"  (config//.;module-login 1)))))


;(def ^:private async-login  config/module-login)

;(def ^:private async-user  (async-login 0))

;(def ^:private async-credentials (str "Base" (b64/string->b64 (str async-user ":"  (async-login 1)))))

(def user-name  (test-action :user_name))

(defn update-user [_] [1])

(defn  get-user [_]  {:name user-name :val {:password "password"
                                            :name user-name
                                            :expire_at nil}})
(defn delete-user [_] [1])

(defn request [url resp pusher]
  (with-fake-http [url (keywordize-header resp)]
    (pusher test-action "test-thread")))

(defn fix-setup-session [t]
  (with-redefs [sm_async_api.dal/update-user update-user
                sm_async_api.dal/get-user get-user
                sm_async_api.dal/delete-user delete-user
                sm_async_api.task.writers/result-writer sm_async_api.task.writers/silent-result-writer
                sm_async_api.task.writers/action-rescheduler sm_async_api.task.writers/silent-action-rescheduler]
    (config/configure "test/");"test/sm_async_api/")
    (session/new-session authorization user-name nil)
    (t)))

(deftest test-sync-pusher-user-mode
  (testing  "Test user mode sync-pusher request structure:"
    (let [url (spy (sync-url test-action))
          resp (request url  responce-OK (sp/pusher-factory :user-mode false))
          {:keys [mode rec-id headers thread]} (:opts resp)
          authorization (when headers (headers "Authorization"))]
      (testing "Url"
        (is (= url (-> resp :opts :url))))
      (testing "mode"
        (is (= :user-mode  mode)))
      (testing "thread"
        (is  (= "test-thread"  thread)))
      (testing "rec-id"
        (is (= "test_id" rec-id)))
      (testing "authorization"
        (is (= "password" authorization)))))

   (testing  "Test sync-pusher proccessor in user mode"
    (let [pusher (sp/pusher-factory :user-mode true)]
      (are  [r p] (= r (sp/processor
                        (request (sync-url test-action) p pusher)
                        "test-thread"))
        tr/NEXT-ACTION  responce-OK  ;result/OK
        tr/NEXT-ACTION  responce-BAD-REQ ;result/OK
        tr/NEXT-ACTION  responce-INTERNAL-ERROR-GENERIC ;result/OK
        tr/NEXT-ACTION  responce-INTERNAL-ERROR ;result/OK
        tr/NEXT-ACTION   responce-NOT-ATHORIZED  ;result/NOT-ATHORIZED 
        tr/RETRY-ACTION  responce-TOO-MANY-TREADS    ;result/TOO-MANY-TREADS ;
        tr/RETRY-ACTION  responce-NO-SERVER-json    ;result/SERVER-NOT-AVAILABLE
        tr/RETRY-ACTION  responce-NO-SERVER-no-json ;result/SERVER-NOT-AVAILABLE
        tr/EXIT-THREAD   responce-ERROR  ;resule/ERROR
        ))))

 (deftest test-sync-pusher-global-mode
  (testing  "Test global mode sync-pusher request structure:"
    (let [resp (request (sync-url test-action)  
                        responce-OK 
                        (sp/pusher-factory :global-mode false))
          {:keys [mode rec-id headers thread]} (:opts resp)
          authorization (when headers (headers "Authorization"))]
      (testing "Url"
        (is (= (sync-url test-action) (-> resp :opts :url))))
      (testing "mode"
        (is (= :global-mode  mode)))
      (testing "thread"
        (is  (= "test-thread"  thread)))
      (testing "rec-id"
        (is (= "test_id" rec-id)))
      (testing "authorization"
        (is (= (config/get-config :global-credentials) authorization)))))
  (testing  "Test sync-pusher proccessor in global mode"
    (let [pusher (sp/pusher-factory :global-mode false)]
      (are  [r p] (= r (sp/processor
                        (request (sync-url test-action) p pusher)
                        "test-thread"))
        tr/NEXT-ACTION  responce-OK  ;result/OK
        tr/NEXT-ACTION  responce-BAD-REQ ;result/OK
        tr/NEXT-ACTION  responce-INTERNAL-ERROR-GENERIC ;result/OK
        tr/NEXT-ACTION  responce-INTERNAL-ERROR ;result/OK
        tr/EXIT-THREAD   responce-NOT-ATHORIZED  ;result/NOT-ATHORIZED 
        tr/RETRY-ACTION  responce-TOO-MANY-TREADS    ;result/TOO-MANY-TREADS ;
        tr/RETRY-ACTION  responce-NO-SERVER-json    ;result/SERVER-NOT-AVAILABLE
        tr/RETRY-ACTION  responce-NO-SERVER-no-json ;result/SERVER-NOT-AVAILABLE
        tr/EXIT-THREAD   responce-ERROR  ;resule/ERROR
        ))))

 (deftest test-asycn-pusher
  (testing   "Test async mode sync-pusher request structure:"
    (let [resp (request (config/get-config :async-action-url) 
                        responce-OK 
                        (sp/pusher-factory :async-mode false))
          {:keys [mode rec-id headers thread body]} (:opts resp)
          authorization (when headers (headers "Authorization"))
          {:keys [UserName TicketID Request]} (json/parse-string body keyword)
          Request (json/parse-string Request keyword)]
      ;(println "opts" (:opts resp))
      (testing "Url"
        (is (= (config/get-config :async-action-url) (-> resp :opts :url))))
      (testing "mode"
        (is (= :async-mode  mode)))
      (testing "thread"
        (is  (= "test-thread"  thread)))
      (testing "rec-id"
        (is (= "test_id" rec-id)))
      (testing "authorization"
        (is (= (config/get-config :async-credentials) authorization)))
      (testing  "Async body form"
        (is (= "test"  UserName))
        (is (= "test-subject" TicketID))
        (is (= "test-action" (Request :action)))
        (is (= "{ \"item-name\": {\"field_1\":\"val1\"}}" (Request :parameters)))
        (is (= "scheduleOnly" (-> Request :execution :mode)))
        (is (= "test schedule" (-> Request :execution :sheduler)))
        (is (= 50 (-> Request :execution :retries)))
        (is (= 20 (-> Request :execution :retiryInterval))))))
  (testing (format "Test async-pusher")
    (let [pusher (sp/pusher-factory :async-mode false)]
      (are  [r p] (= r (sp/processor
                        (request (config/get-config :async-action-url) p pusher)
                        "test-thread"))
        tr/NEXT-ACTION  responce-OK  ;result/OK
        tr/NEXT-ACTION  responce-BAD-REQ ;result/OK
        tr/NEXT-ACTION  responce-INTERNAL-ERROR-GENERIC ;result/OK
        tr/NEXT-ACTION  responce-INTERNAL-ERROR ;result/OK
        tr/EXIT-THREAD   responce-NOT-ATHORIZED  ;result/NOT-ATHORIZED 
        tr/RETRY-ACTION  responce-TOO-MANY-TREADS    ;result/TOO-MANY-TREADS ;
        tr/RETRY-ACTION  responce-NO-SERVER-json    ;result/SERVER-NOT-AVAILABLE
        tr/RETRY-ACTION  responce-NO-SERVER-no-json ;result/SERVER-NOT-AVAILABLE
        tr/EXIT-THREAD   responce-ERROR  ;resule/ERROR
        ))))


(use-fixtures :once fix-setup-session)