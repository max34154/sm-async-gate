  
(ns ^:api sm-async-api.core-test
  {:clj-kondo/config  '{:linters {:unresolved-symbol
                                  {:exclude [testing use-fixtures deftest is are]}
                                  :refer-all {:level :off}}}}
  (:require [clojure.java.io :as io]
            ;[org.httpkit.server :as httpkit]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [sm_async_api.config :as config]
            ;[sm_async_api.dal.configure :refer [configure-database]]
            ;[sm_async_api.dal.globals :refer [task-action request-action]]
            [clojure.test :refer :all]
            [sm-async-api.core :refer [startup shutdown]]))

(def basic-auth ["max" "Shis!!9SD234ds123"])
(def action-url  "http://localhost:3000/")

(def task-url  "http://localhost:3001/")

(def headers {"content-type"  "application/json"})
(def headers_png {"content-type"  "image/png"
                  "content-disposition" "attachment;filename=obsluzhivanie.png"})

(def standart_body
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"S\",
    \"execution_retries\":65,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")

(def parameters_only_body
  "{ \"parameters\":\"something\"}")

(def standart_body_imidiate_execution
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"I\",
    \"execution_retries\":65,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")

(def standart_body_mix_execution
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"IS\",
    \"execution_retries\":65,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")


(def zero_body "")

(comment "
:remote-addr 127.0.0.1, 
:params {:action_id 6144B248-1-1AEF}, 
:route-params {:action_id 6144B248-1-1AEF}, 
:headers {accept-encoding gzip, deflate, authorization Basic bWF4OlNoaXMhITlTRDIzNGRzMTIz, content-disposition attachmentfilename=obsluzhivanie.png, content-length 31305, content-type image/png, host localhost:3000, user-agent http-kit/2.0}, 
:async-channel #object[org.httpkit.server.AsyncChannel 0x15b8fc38 /127.0.0.1:3000<->/127.0.0.1:52234], 
:server-port 3000, 
:content-length 31305, 
:websocket? false, 
:content-type image/png, 
:character-encoding utf8, 
:uri /Action/6144B248-1-1AEF/attachments, 
:server-name localhost, 
:user_name max, 
:query-string nil, 
:body #object[org.httpkit.BytesInputStream 0x3bdf9642 BytesInputStream[len=31305]], 
:multipart-params {}, :scheme :http, :request-method :post}"
)

(def waiting_body
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"S\",
    \"status\":\"W\",
    \"execution_retries\":65,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")

(def correct_bodies
  [[standart_body "Standart body"]
   [standart_body_imidiate_execution "Standart body, immidate execution mode"]
   [standart_body_mix_execution "Standart body, mixed execution mode"]
   [parameters_only_body  "Body without scheduling options"]
   [zero_body  "Empty body"]])


(deftest test-post-correct-body-action
  (testing "Post action: correct body"
    (doseq [[body* description] correct_bodies]
      (testing description
        (let [{:keys [status]}
              @(http/post (str action-url "FOSpp/SD123232/add")
                          {:basic-auth basic-auth
                           :headers headers
                           :body body*})]
          (is (= 200 status)))))))

(deftest test-post-enriched-action
  (with-local-vars [rec_id nil]
    (testing "Phase 1: post action"
      (let [{:keys [status  body]}
            @(http/post (str action-url "FOSpp/SD123232/add")
                        {:basic-auth basic-auth
                         :headers headers
                         :body waiting_body})]
        (when (not (nil? body))
          (var-set rec_id (:rec_id (json/parse-string body keyword))))
        (is (= 200 status))
        (is (not (nil? @rec_id)))))
    (testing (str "Phase 2: post one file into" @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status]}
              @(http/post (str action-url "Action/" @rec_id "/attachments")
                          {:basic-auth basic-auth
                           :headers headers_png
                           :body  (clojure.java.io/file "obsluzhivanie.png")})]
          (is (= 200 status)))))
    (testing (str "Phase 3: run action " @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status  body]}
              @(http/put (str action-url "Action/" @rec_id "/run")
                         {:basic-auth basic-auth
                          :headers headers
                          :body ""})]
          (is (and (= 200 status)
                   (not (nil? body))
                   (= 0 (:ReturnCode (json/parse-string body keyword))))))))))


(def wrong_execution_mode
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"SX\",
    \"execution_retries\":65,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")

(def wrong_execution_retries_1
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"S\",
    \"execution_retries\":101,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")

(def wrong_execution_retries_2
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"S\",
    \"execution_retries\":-1,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")


(def wrong_retry_interval_1
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"S\",
    \"execution_retries\":-1,
    \"retry_interval\":1,
    \"parameters\":\"something\"}")


(def wrong_status
  "{ \"schedule_name\":\"test_sch\",\"execution_mode\":\"S\",
    \"status\":\"W1\",
    \"execution_retries\":65,
    \"retry_interval\":305,
    \"parameters\":\"something\"}")

(def wrong_bodies
  [[wrong_execution_mode "Wrong execution mode"]
   [wrong_execution_retries_1 "Number of retries above high level"]
   [wrong_execution_retries_2  "Number of retries below 0"]
   [wrong_retry_interval_1 "Retiry interval below 0"]
   [wrong_status "Wrong status code"]])

(deftest test-post-wrong-body-action
  (testing "Post action: incorrect body "
    (doseq [[body* description] wrong_bodies]
      (testing description
        (let [{:keys [status]}
              @(http/post (str action-url "FOSpp/SD123232/add")
                          {:basic-auth basic-auth
                           :headers headers
                           :body body*})]
          (is (= 422 status)))))))

;;
;; post action format 
;; /WebServiceName/ObjectID/ActionName
;; body may conaints 
;; 
;;    "schedule_name" - shedule name, string optional  
;;    "execution_mode": - reserved for future use 
;;    "execution_retries" - maximum execution attempts, number optional
;;    "retry_interval" - interval between attempts in seconds 
;;    "parameters" - optional parametes string 
;;    "status"  - 


;(let [{:keys [status headers body error] :as resp}
;      @(http/post (str action-url "FOSpp/SD123232/add")
;                  {:basic-auth basic-auth
;                   :headers {"content-type"  "application/json"}
;                   :body standart_body})]
;  (if error
;    (println "Failed, exception: " error)
;    (println "HTTP GET success: " status "body" body "headers" headers)))


(comment (http/post (str action-url "FOSpp/SD123232/add")
                    {:basic-auth basic-auth
                     :headers headers
                     :body standart_body}))

(comment (http/request
          {:url "http://127.0.0.1:3000/FOSpp/SD123435/add"
           :method :post
           :basic-auth basic-auth
           :header {"Content-Type"  "application/json"}
           :insecure? true
           :deadlock-guard?  false
           :body standart_body}))


(comment (http/request
          {:url "http://localhost:3000/Action/60E69932-2-1AEF"
           :method :get
           :basic-auth basic-auth
           :header {"Content-Type"  "application/json"}
           :insecure? true
           :deadlock-guard?  false
           :body standart_body}))

(comment (http/get "http://rbc.ru"))

(comment (http/post "http://127.0.0.1:3000/FOSpp/SD123435/add"
                    {:basic-auth basic-auth
                     :header {"Content-Type"  "application/json"}
                     :body standart_body}))


(defn fix-test-core [t]
  (startup  3000 "test/" )
  (swap! sm_async_api.config/config update-in [:config :auth-url] (fn [_]"http://212.11.152.7:13080/SM/9/rest/asyncuser"))
  (t)
  (shutdown))


(use-fixtures :once fix-test-core)
