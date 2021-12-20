  
(ns ^:api sm-async-api.full-test
  {:clj-kondo/config  '{:linters {:unresolved-symbol
                                  {:exclude [testing use-fixtures deftest is are]}
                                  :refer-all {:level :off}}}}
  (:require
   [org.httpkit.client :as http]
   [cheshire.core :as json]
   [sm_async_api.config :as config]
   [clojure.test :refer :all]
   [sm_async_api.dal.user :as dal-u]
   [sm_async_api.dal.globals :as dal-g]
   [sm_async_api.session :as session]
   ;[sm_async_api.dal.configure :refer [execute-script]]
   [sm-async-api.task.sm_fake_resp :as SM]
   [sm_async_api.utils.base64 :refer [string->b64]]
   [taoensso.timbre :as timbre]
   [clojure.java.io]
   [sm-async-api.hook-test :as hook_test]
   [sm_async_api.utils.reflector :refer [relector-set-responce
                                         reflector-start
                                         reflector-stop]]
   [sm-async-api.core :refer [startup shutdown]]))

(def basic-auth ["max" "Shis!!9SD234ds123_"])

(defn update-user [_] [1])

(defn  get-user [user-name]  {:name user-name :val {:password (string->b64 (str (basic-auth 0) ":" (basic-auth 1)))
                                                    :name (basic-auth 0)
                                                    :expire_at nil}})
(defn delete-user [_] [1])


(def test-users [(basic-auth 0)])

(def action-url  "http://localhost:3000/")

(def task-url  "http://localhost:3001/")

(def headers {"content-type"  "application/json"})
(def headers_png {"content-type"  "image/png"
                  "content-disposition" "attachment;filename=obsluzhivanie.png"})

(defn post-file 
  ([rec-id file-name content-type] (post-file rec-id file-name content-type basic-auth))
  ([rec-id file-name content-type auth]
  @(http/post (str action-url "Action/" rec-id "/attachments")
              {:basic-auth auth
               :headers {"content-type"  content-type
                         "content-disposition" (str "attachment;filename=" file-name)}
               :body  (clojure.java.io/file file-name)})))


(def request-body
  {:schedule_name "test_sch"
   :execution_mode "S"
   :execution_retries 65
   :retry_interval  305
   :tag "hook-parametric"
   :parameters  "{\"something\":\"something\"}"})

(def standart_body
  (json/generate-string
   request-body))

(def parameters_only_body
  "{ \"parameters\":\"something\"}")

(def standart_body_imidiate_execution
  (json/generate-string
   (assoc request-body  :execution_mode "I")))

(def standart_body_mix_execution
  (json/generate-string
   (assoc request-body  :execution_mode "IS")))


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
:multipart-params {}, :scheme :http, :request-method :post}")

(def waiting_body_async
  (json/generate-string
   (assoc request-body  :status "W")))

(def waiting_body_sync
  (json/generate-string
   (assoc request-body  :status "W" :execution_mode "I")))

(def correct_bodies
  [[standart_body "Standart body" :has-hook]
   [standart_body_imidiate_execution "Standart body, immidate execution mode" :has-hook]
   [standart_body_mix_execution "Standart body, mixed execution mode" :has-hook]
   [parameters_only_body  "Body without scheduling options" :no-hook]
   [zero_body  "Empty body" :no-hook]])


(defn post-action [action]
  (let [{:keys [status body]} @(http/post (str action-url "FOSpp/SD123232/add")
                                          {:basic-auth basic-auth
                                           :headers headers
                                           :body (action 0)})]
    {:description (action 1)
     :status status
     :rec-id (:rec_id (json/parse-string body keyword))
     :tag (when (= (action 2) :has-hook) true)}))

(defn get-action 
  ([action] (get-action action basic-auth))
  ([action auth]
  (let [{:keys [status body]} @(http/get (str action-url "Action/" (:rec-id action) "/result")
                                         {:basic-auth auth})
        {:keys [res_status result]} (json/parse-string body keyword)]
    (assoc action :status status :result result :res-status res_status :body body))))

(defn get-res-status [result]
  (try
    (:res_status (json/parse-string (:body result) keyword))
    (catch Exception _ (println "incorrect body " (:body result))  nil)))


(defn get-message-delivery-status [id]
  (:status ((@dal-g/hook-action :check-message-delivery) id)))

(defn get-attachment-copy-status [id status-list]
  (reduce (fn [_ {:keys [cp_status]}]
            (if (some #(= cp_status %) status-list)
              true
              (reduced false)))  true
          ((@dal-g/attachment-action  :get-attachments-list) {:route-params {:action_id id}})))

(deftest  test-post-and-check
  (testing "Post action and get result"
    (relector-set-responce SM/responce-OK)
    (let [result (map post-action  correct_bodies)]
      (doseq [r result]
        (testing (format "Posted %s with id %s" (:description r) (:rec-id r))
          (is (= 200 (:status r)))))
      (Thread/sleep 10000)
      (doseq [r (map get-action result)]
        (testing (format " Get %s with id %s " (:description r) (:rec-id r))
          (is (some? (get-res-status r))
              (when (:tag r)
                (is (= 200 (get-message-delivery-status (:rec-id r)))))))))))


(deftest test-post-enriched-action-sync
  (relector-set-responce SM/responce-OK-withID)
  (with-local-vars [rec_id nil]
    (testing "Phase 1: post action"
      (let [{:keys [status  body]}
            @(http/post (str action-url "FOSpp/SD123232/add")
                        {:basic-auth basic-auth
                         :headers headers
                         :body waiting_body_sync})]
        (when (not (nil? body))
          (var-set rec_id (:rec_id (json/parse-string body keyword))))
        (is (= 200 status))
        (is (not (nil? @rec_id)))))
    (testing (str "Phase 2.1: post first file into" @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status]}
              (post-file @rec_id "MP900216006.JPG" "image/jpg")]
          (is (= 200 status)))))
    (testing (str "Phase 2.2: post second file into" @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status]}
              (post-file @rec_id "obsluzhivanie.png" "image/png")]
          (is (= 200 status)))))
    (testing (str "Phase 3: run action " @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status  body]}
              @(http/post (str action-url "Action/" @rec_id "/run")
                          {:basic-auth basic-auth
                           :headers headers
                           :body ""})]
          (is (= 200 status))
          (is (not (nil? body)))
          (is (= 0 (:ReturnCode (json/parse-string body keyword)))))))
    (Thread/sleep 10000)
    (testing (str "Phase 4: get result " @rec_id)
      (is (some? (-> {:rec-id @rec_id}
                     get-action
                     get-res-status)))
      (is (= 200 (get-message-delivery-status  @rec_id))))
    (testing (str "Phase 5: check attachment copy " @rec_id)
      (is (= true  (get-attachment-copy-status @rec_id '(200)))))))



(deftest test-post-enriched-action-async
  (relector-set-responce SM/responce-OK-withID)
  (with-local-vars [rec_id nil]
    (testing "Phase 1: post action"
      (let [{:keys [status  body]}
            @(http/post (str action-url "FOSpp/SD123232/add")
                        {:basic-auth basic-auth
                         :headers headers
                         :body waiting_body_async})]
        (when (not (nil? body))
          (var-set rec_id (:rec_id (json/parse-string body keyword))))
        (is (= 200 status))
        (is (not (nil? @rec_id)))))
    (testing (str "Phase 2.1: post first file into" @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status]}
              (post-file @rec_id "MP900216006.JPG" "image/jpg")]
          (is (= 200 status)))))
    (testing (str "Phase 2.2: post second file into" @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status]}
              (post-file @rec_id "obsluzhivanie.png" "image/png")]
          (is (= 200 status)))))
    (testing (str "Phase 3: run action " @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status  body]}
              @(http/post (str action-url "Action/" @rec_id "/run")
                          {:basic-auth basic-auth
                           :headers headers
                           :body ""})]
          (is (= 200 status))
          (is (not (nil? body)))
          (is (= 0 (:ReturnCode (json/parse-string body keyword)))))))
    (Thread/sleep 10000)
    (testing (str "Phase 4: get result " @rec_id)
      (is (some? (-> {:rec-id @rec_id}
                     get-action
                     get-res-status)))
      (is (= 200 (get-message-delivery-status  @rec_id))))
    (testing (str "Phase 5: check attachment copy " @rec_id)
      (is (= true  (get-attachment-copy-status @rec_id '(200)))))))

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

;; Message template configuration
;;
;;
;;


(defn fix-test-core [t]
  (timbre/set-level! :debug)
  ;(startup  3000 "test/config/run/")
  (startup  {:-port 3000 :-path "test/config/run/" :-db-clean true })
  ;(execute-script "TRUNCATE TABLE ASYNC.ATTACHMENT;TRUNCATE TABLE ASYNC.RESPONCE;DELETE FROM ASYNC.REQUEST;")
  ;(execute-script "TRUNCATE TABLE ASYNC.HOOK;")
  ;(execute-script "TRUNCATE TABLE ASYNC.MESSAGE_LOG;")
  (reflector-start)
  ((@dal-g/hook-action :add-template) (update-in hook_test/hook-parametric [:url] hook_test/set-url))
  ((@dal-g/hook-action :add-template) (update-in hook_test/hook-standart [:url] hook_test/set-url))
  ;(redef-delete-hook-action)

  (swap! sm_async_api.config/config update-in [:config :auth-url] (fn [_] "http://212.11.152.7:13080/SM/9/rest/asyncuser"))
  (with-redefs [dal-u/update-user (delay update-user)
                dal-u/get-user (delay get-user)
                dal-u/delete-user  (delay delete-user)]
    (session/new-session (string->b64 (str (basic-auth 0) ":" (basic-auth 1))) (basic-auth 0) nil)
    (t))
  (reflector-stop)
  (shutdown))



(use-fixtures :once fix-test-core)

