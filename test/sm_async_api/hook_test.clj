(ns ^:hook sm-async-api.hook-test
  (:require
   [clojure.test :refer [testing use-fixtures deftest is are]]
   [clojure.string :as s]
   [sm_async_api.config :as config]
   [sm-async-api.task.sm_fake_resp :refer [http-post-return-base]]
   [sm_async_api.utils.reflector :refer [relector-set-responce
                                         reflector-start
                                         reflector-stop]]
   [cheshire.core :as json]
   [sm_async_api.dal.configure :refer [configure-database
                                       execute-script]]
   [sm-async-api.hook.hook :as hook]
   [sm_async_api.hook.globals :as hook-g]
   [sm_async_api.dal.hook :as dal-h]
   [sm_async_api.dal.globals :as dal-g]
   [sm_async_api.utils.dispatcher :as dispatcher-g]
   [taoensso.timbre :as timbre]
   [yaml.core :as yaml]
   [sm_async_api.hook.dispatcher :refer [start-messengers
                                         stop-messengers]]))

(def test-path "test/config/run/")

(def message-reciver "http://localhost:13080")

(def test-users ["max", "test", ["dima","vs"], ["other1", "other2"]])

(defmacro reflector-OK []
  `(relector-set-responce (assoc http-post-return-base :body "{}")))

(defmacro reflector-NOT-OK
  ([]
   `(reflector-NOT-OK 500))
  ([code]
   `(relector-set-responce (assoc http-post-return-base :body "{}" :status ~code))))


(def hook-standart
  {:name "test-hook"
   :user_name "0"
   :method "post"
   :url message-reciver
   :max_retries 1
   :retry_interval 1})

(defn insert_tag [tag] (str hook/tag-border tag hook/tag-border))

(def hook-parametric
  {:name "hook-parametric"
   :headers (str "{\"Content-Type\":\"application/json\","
                 "\"Connection\": \"keep-alive\""
                 ;",\"Authorization\":\"" (insert_tag "REQ_ID")  "\""
                 "}")
   :user_name "0"
   :method "post"
   :url (str message-reciver "/?user-name=" (insert_tag "USER_NAME"))
   :body (str "{\"RC\":" (insert_tag "RC") ","
              "\"MS\": " (insert_tag "MS") ","
              "\"REQ_ID\":\"" (insert_tag "REQ_ID")  "\","
              "\"STATUS\": " (insert_tag "STATUS") ","
              "\"FullBody\":" (insert_tag "BODY")  "}")
   :max_retries  3
   :retry_interval 11})

(defn set-url [_]
  (str "http://" (-> @config/config :config :module_ip) (-> @config/config :config :base-path)
       "test/message/reciver"))

(defn repsonce-body [message]
  (str "{\"ReturnCode\":0,"
       "\"Messages\":[\""
       (if (empty? message) "It 's OK" message)
       "\"]}"))

(def sm-responce-template {:opts {:tag "hook-parametric"}
                           :status 200
                           :body (repsonce-body "")
                           :content-type "application/json"})

(defn build-message [user sm-responce-template]
  (if (vector?  user)
    (let [lngth (count user)]
      (fn [pos]
        (-> sm-responce-template
            (update-in  [:opts] assoc
                        :user-name (user (rand-int lngth))
                        :rec-id (config/get-uid))
            (assoc :body (repsonce-body (str "Request index " pos))))))
    (fn [pos]
      (-> sm-responce-template
          (update-in  [:opts] assoc
                      :user-name user
                      :rec-id (config/get-uid))
          (assoc :body (repsonce-body (str "Request index " pos)))))))

(defn  build-messages
  [user qty sm-responce-template]
  (doall (map  (build-message user sm-responce-template) (range qty))))

(defn  build-all-user-messages
  [qty sm-responce-template]
  (reduce #(concat %1 (build-messages %2 qty sm-responce-template)) {} test-users))

(defn post-message [{:keys [opts status body content-type]}]
  (hook/post-message  opts status body content-type))

(defn post-messages [messages]
  (println (format
            "%s messages posted"
            (reduce (fn [count message] (post-message message) (inc count)) 0 messages))))

(def dump-messages (delay (dal-h/dump-message-queue-factory (@config/config :database))))

(def get-message (delay (dal-h/get-message-by-id-factory (@config/config :database))))

(def get-message-queue-length (delay (dal-h/get-message-queue-length-factory (@config/config :database))))

(defn fix-read-config [t]
  (timbre/set-level! :error)
  (config/configure test-path)
  (configure-database)
  (execute-script "TRUNCATE TABLE ASYNC.HOOK;")
  ((@dal-g/hook-action :add-template) (update-in hook-parametric [:url] set-url))
  ((@dal-g/hook-action :add-template) (update-in hook-standart [:url] set-url))
  (reflector-start)
  (t)
  (reflector-stop))


(deftest  test-infrastructure
  (stop-messengers)
  (is (= 0 (reduce dispatcher-g/count-readers 0 @hook-g/online-messangers))))

#_(defn kick? [t] (not (true? (:no-kick (meta t)))))


(defn fix-run-messegners [t]
  (execute-script "TRUNCATE TABLE ASYNC.MESSAGE;")
  (if (=  (start-messengers) 1)
    (timbre/fatal "Messenger configuration error")
    (t))
  (stop-messengers))



(use-fixtures :each fix-run-messegners)
(use-fixtures :once fix-read-config)




(deftest test-post-message
  (let [messages (build-messages "max" 1 sm-responce-template)]
    (post-messages messages)
    (is (= (count messages) (@get-message-queue-length)))))

(deftest test-post-messages
  (let [messages (build-all-user-messages 5 sm-responce-template)]
    (post-messages messages)
    (is (= (count messages) (@get-message-queue-length)))))

(deftest test-responce-OK
  (reflector-OK)
  (let [messages (build-all-user-messages 5 sm-responce-template)]
    (post-messages messages)
    (println "Found message " (@get-message-queue-length))
    (Thread/sleep 5000)
    (is (= 0 (@get-message-queue-length)))))


(deftest test-responce-error-recovery
  (reflector-NOT-OK)
  (let [messages (build-all-user-messages 5 sm-responce-template)]
    (post-messages messages)
    (println "Found message " (@get-message-queue-length))
    (Thread/sleep 5000)
    (is (= (count messages) (@get-message-queue-length)))
    (is (= (* (count messages) (dec (hook-parametric :max_retries)))
           (reduce #(+ %1 (:attempt %2)) 0 (@dump-messages))))
    (reflector-OK)
    (Thread/sleep 20000)
    (is (= 0 (@get-message-queue-length)))))


