(ns ^:dal sm-async-api.dal-test
  (:require
   [clojure.test :refer [testing use-fixtures deftest is are]]
   [clj-time.coerce :as clj-time]
   [clojure.string :as str]
   [sm_async_api.config :as config]
   [cheshire.core :as json]
   [sm_async_api.dal.configure :refer [configure-database
                                       execute-script
                                       reload-hook-templates
                                       unload-hook-template]]
   [sm_async_api.dal.globals :as g :refer [task-action
                                           request-action
                                           hook-action]]
   ;[sm_async_api.session :as session]
   [sm_async_api.utils.macro :refer [tod-seconds]]
   [sm_async_api.dal.hook :as dal-h]
   [sm_async_api.dal.user :as dal-u]
   [sm-async-api.hook.hook :as hook])
  #_{:clj-kondo/ignore [:unused-import]}
  (:import [java.sql SQLException SQLIntegrityConstraintViolationException BatchUpdateException]))

(def test_user  "max")
(def test_schedule "test_sch")
(def test_chunk_size 3)

(def ^:dynamic *test_data*)

(def ^:dynamic *db-type*)

;(def test_data (slurp "test/sm_async_api/test_data.sql"))

;; !! Some delays still active after test finished
;; !! Due to this problem is not possible to test both DB at once 
;; !! REPL must be restarted between therpasses.
#_(def configs [;{:db-type "h2", :path "test/", :test_data (slurp "test/sm_async_api/test_data_h2.sql")}
                {:db-type "postgres", :path "test/config/postgres/", :test_data (slurp "test/sm_async_api/test_data_pg.sql")}])

#_(defn fix-test-db [t]
    (doseq [db configs]
      (binding [*test_data* (:test_data db)
                *db-type* (:db-type db)]

        (config/configure (:path db))
        (println "Run for dbtype " *db-type* " with config " (:path db))
        (configure-database)
        (t))))

(defn fix-test-db [t]
  (config/configure "test/config/run/")
  (configure-database)
  (t))

(defn fix-test-data  [t]
  (execute-script "TRUNCATE TABLE ASYNC.ATTACHMENT; TRUNCATE TABLE ASYNC.MESSAGE; TRUNCATE TABLE ASYNC.HOOK; TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;")
  (execute-script (slurp "test/config/run/test_data.sql"))
  (t))

(use-fixtures :once fix-test-db)

(use-fixtures :each fix-test-data)

(deftest test-lock-and-cleanup-tasks
  (testing "Lock and cleanup"
    (let [lock-tasks (@task-action :lock)
          cleanup-exited-worker (@task-action :cleanup)]
      (testing (format "Lock 3 tasks for user %s and schedule %s" test_user test_schedule)
        (is (= test_chunk_size (lock-tasks test_user test_schedule test_chunk_size))))

      (testing (format "Reliase all tasks for user %s and schedule %s" test_user test_schedule)
        (is (= test_chunk_size (cleanup-exited-worker test_user)))))))

(deftest test-standart-task-operations
  (testing "Standart operations"
    (let [lock-tasks (@task-action :lock)
          get-tasks (@task-action :get)
          post-task-result (@task-action :post-result)]
      (testing (format "Lock 3 tasks for user %s and schedule %s " test_user test_schedule)
        (is (= test_chunk_size (lock-tasks test_user test_schedule test_chunk_size))))

      (testing "Get tasks"
        (is (= 3 (count  (get-tasks test_user)))))

      (testing "Write result"
        (let [result
              (first (post-task-result (:req_id (first
                                                 (get-tasks test_user)))
                                       "{\"Result Code\": 0}"))]
          (is (= 1 result))))

      (testing "Attempt to write result twice for same task"
        (is  (thrown? BatchUpdateException
                      (post-task-result  (:req_id (first ((@task-action :get-worker-results)  test_user)))
                                         "{\"Result Code\": 0}")))
        #_(is (thrown? SQLIntegrityConstraintViolationException
                       (post-task-result  (:req_id (first ((@task-action :get-worker-results)  test_user)))
                                          "{\"Result Code\": 0}")))))))

(defn check_reschedule  [prev_req_id prev-att  prev-next_run prev-retry_interval]
  ((@task-action :reschedule) prev_req_id)
  (let [{:keys [req_id attempt next_run]}
        (first ((@request-action :get) {:route-params {:action_id prev_req_id}}))]
    (and (not (nil? req_id))
         (= (- prev-att attempt) 1)
         (= (- (clj-time/to-long next_run)
               (clj-time/to-long prev-next_run))
            (* prev-retry_interval 1000)))))

(deftest test-lock-and-release-task
  (testing "Lock and release"
    ((@task-action :lock) test_user test_schedule test_chunk_size)
    (let [{:keys [req_id attempt next_run retry_interval]}
          (first ((@task-action :get) test_user))
          result (if (nil? req_id) false
                     (check_reschedule  req_id attempt next_run retry_interval))]
      (is (some? req_id))
      (is (= true result)))))



(def ^:private max-max-retries  200)

(def ^:private min-retry-interval  10)

(def hook-standart
  {:name "test-hook"
   :user_name "0"
   :method "post"
   :url "loocalhost:13080"
   :max_retries  (dec max-max-retries)
   :retry_interval 1})

(def my-hook (assoc hook-standart :user_name "hook owner"))


(def not-my-hook (assoc hook-standart :user_name "other owner"))

(def retry_interval_20 (assoc hook-standart :name "retry_interval_20"
                              :retry_interval 20))

(def retry_interval_nil (dissoc
                         (assoc hook-standart :name "retry_interval_nil")
                         :retry_interval))
(def max-retiries-nil (dissoc
                       (assoc hook-standart :name "max-retiries-nil")
                       :max_retries))

(def max-retiries-0 (assoc hook-standart :name "max-retiries-0"
                           :max_retries 0))

(def max-retiries-201 (assoc hook-standart :name "max-retiries-201"
                             :max_retries 201))



(defn- ->opts
  ([hook]
   (->opts  (:name hook) (:user_name hook)))
  ([tag user-name]
   {:tag tag
    :user-name user-name}))

(deftest test-hook-operations
  (when (nil? @hook-action) (throw (Exception. "hook-action is nil")))
  (when (nil? (@hook-action :add-template)) (throw (Exception. "hook-action :add-template is nil")))
  (let [get-template (@hook-action :get-template)
        add-template (@hook-action :add-template)
        get-all-templates (@hook-action :get-all-templates)
        get-all-available-templates (@hook-action :get-all-available-templates)
        delete-template (@hook-action :delete-template)]
    (add-template hook-standart)
    (add-template my-hook)
    (add-template not-my-hook)
    (add-template retry_interval_nil)
    (add-template max-retiries-nil)
    (add-template max-retiries-0)
    (add-template max-retiries-201)
    (add-template retry_interval_20)

    (testing "Add hooks and get generick hook"
      (is (= "0"  (:user_name (first (get-template (->opts (hook-standart :name) "unknown")))))))

    (testing "Get personal hook"
      (is (= "hook owner"  (:user_name (first (get-template (->opts my-hook)))))))

    (testing "Retry interval validation"
      (is (= min-retry-interval
             (:retry_interval (first (get-template (->opts hook-standart))))))
      (is (= min-retry-interval
             (:retry_interval (first (get-template (->opts retry_interval_nil))))))
      (is (= (retry_interval_20 :retry_interval)
             (:retry_interval (first (get-template (->opts retry_interval_20)))))))

    (testing "Max retries validation"
      (is (= 1
             (:max_retries (first (get-template (->opts max-retiries-nil))))))
      (is (= 1
             (:max_retries (first (get-template (->opts max-retiries-0))))))
      (is (= max-max-retries
             (:max_retries (first (get-template (->opts max-retiries-201))))))
      (is (= (hook-standart :max_retries)
             (:max_retries (first (get-template (->opts hook-standart)))))))

    (testing "Get all templates "
      (is (= 8 (count (get-all-templates)))))

    (testing "Get all available templates "
      (is (= 7 (count (get-all-available-templates "hook owner")))))

    (testing  "Delete hook"
      (is (= 1 (first (delete-template  "test-hook" "hook owner"))))
      (is (= "0"  (:user_name (first (get-template (->opts my-hook)))))))))


(def no-method-hook (dissoc (assoc hook-standart :name "no-method")))

(def wrong-method-hook (assoc hook-standart :name "no-method" :method "wrong"))

(def get-method-hook (assoc hook-standart :name "get-method" :method "get"))

(def post-method-post (assoc hook-standart :name "post-method" :method "post"))

(def put-method-put (assoc hook-standart :name "put-method" :method "put"))

;(def put-method-delete (assoc hook-standart :name "delete" :method "delete"))

(deftest test-hook-method
  (let [get-template (@hook-action :get-template)
        add-template (@hook-action :add-template)]
    (testing "Correct hook method"
      (are  [r p] (= r (do (add-template p)
                           (:method (first (get-template (->opts p))))))
        "post" no-method-hook
        "get"  get-method-hook
        "post" post-method-post
        "put" put-method-put))
    (testing "Wrong hook method"
      (is (thrown? AssertionError (add-template wrong-method-hook))))))


(deftest test-onload-reload
  (let [get-all-templates (@hook-action :get-all-templates)
        add-template (@hook-action :add-template)]
    (add-template hook-standart)
    (add-template my-hook)
    (add-template retry_interval_nil)
    (add-template max-retiries-nil)
    (add-template max-retiries-0)
    (add-template max-retiries-201)
    (add-template retry_interval_20)
    (testing "Get all templates "
      (is (= 7 (count (get-all-templates)))))
    (testing "Unload - clean db - reload"
      (unload-hook-template "test/")
      (execute-script "TRUNCATE TABLE ASYNC.HOOK;")
      (reload-hook-templates (@config/config :database) "test/" true)
      (is (= 7 (count (get-all-templates)))))
    (testing "Delete template "
      (let [{:keys [name user_name]} (first (get-all-templates))]
        ((:delete-template @hook-action) name user_name)
        (is (= 6 (count (get-all-templates))))))
    (testing "Reload without cleaning db"
      (reload-hook-templates (@config/config :database) "test/" true)
      (is (= 7 (count (get-all-templates)))))))

(defn insert_tag [tag] (str hook/tag-border tag hook/tag-border))

(def hook-parametric
  {:name "hook-parametric"
   :headers (str "{\"Content-Type\":\"application/json\","
                 "\"Connection\": \"keep-alive\","
                 "\"Authorization\":\"" (insert_tag "REQ_ID")  "\"}")
   :user_name "0"
   :method "post"
   :url (str "loocalhost:13080/?user-name=" (insert_tag "USER_NAME"))
   :body (str "{\"RC\":" (insert_tag "RC") ","
              "\"MS\": " (insert_tag "MS") ","
              "\"STATUS\": " (insert_tag "STATUS") ","
              "\"FullBody\":" (insert_tag "BODY")  "}")
   :max_retries  10
   :retry_interval 11})

(defn decode-message [message]
  (-> message
      (assoc :headers  (json/parse-string (message :headers)))
      (assoc :body (json/parse-string (message :body)))))

(defn get-req-id [message]  ((message :headers) "Authorization"))

(defn get-user-name [message]  ((str/split (message :url) #"user-name=") 1))

(defn get-RC [message]  ((message :body) "RC"))

(defn get-MS [message]  ((message :body) "MS"))

(defn get-STATUS [message]  ((message :body) "STATUS"))

(defn get-FullBody [message]
  ((message :body) "FullBody"))

(def post-message-request {:body {:status 200
                                  :tag "hook-parametric"
                                  :parameters (str "{\"ReturnCode\":0,"
                                                   "\"Messages\":[\"It's OK\"]}")}})

(deftest test-message-operations
  ((@hook-action :add-template) hook-parametric)
  (testing "Create message "
    (hook/post-message  post-message-request)
    (let [messages ((@hook-action :message-reader) "test-messanger" 10 dal-h/global-lock-condition-global-only)
          message (decode-message (first messages))]
      (is (= 200 (get-STATUS message)))
      (is (= 0 (get-RC message)))
      (is (= (hook-parametric :max_retries) (message :attempt)))
      (is (= (hook-parametric :retry_interval) (message :retry_interval)))
      (is (= ["It's OK"] (get-MS message)))
      (is (=  (json/parse-string (-> post-message-request :body :parameters))  (get-FullBody message)))
      (is g/default-sm-user-name (get-user-name message))
      (testing "Reschedule message "
        (let [id (message :id)
              prev-next_run (message :next_run)
              prev-retry_interval (* 1000 (message :retry_interval))]
          ((@hook-action :reschedule) id)
          (Thread/sleep (+ prev-retry_interval 1000))
          (let [{:keys [attempt next_run]} (first ((@hook-action :message-reader) "test-messanger" 10 dal-h/global-lock-condition-global-only))]
            (is (= (dec (hook-parametric :max_retries)) attempt))
            (is (= (- (clj-time/to-long next_run)
                      (clj-time/to-long prev-next_run))
                   prev-retry_interval)))))
      (testing "Message queue length "
        (is (= 1 ((@hook-action :get-message-queue-length)))))
      (testing "Delete expired message"
        ((@hook-action :reschedule) (message :id))
        (Thread/sleep (+ (* 1000 (message :retry_interval)) 1000))
        ((@hook-action :delete-expired) 10)
        (is (= 1 ((@hook-action :get-message-queue-length))))
        ((@hook-action :delete-expired) 0)
        (is (= 0 ((@hook-action :get-message-queue-length))))))))

(deftest test-user-operations
  (testing "Add new user "
    (do
      (@dal-u/update-user  {:name "new-user"
                            :password "his-password"
                            :expire_at  (tod-seconds)})
      (let [result (:val (@dal-u/get-user "new-user"))]
        (is (= "new-user" (result :name)))
        (is (= "his-password" (result :password)))
        (is (some? (result :password))))))
  (testing "Delete user"
    (do (@dal-u/delete-user "new-user")
        (is (nil? (@dal-u/get-user "new-user"))))))
