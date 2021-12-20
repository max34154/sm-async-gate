(ns ^:api sm-async-api.integration-test
  {:clj-kondo/config  '{:linters {:unresolved-symbol
                                  {:exclude [testing use-fixtures deftest is are]}
                                  :refer-all {:level :off}}}}
  (:require
   [org.httpkit.client :as http]
   [cheshire.core :as json]
   ;[sm_async_api.config :as config]
   [clojure.test :refer :all]
   [sm-async-api.full-test :as full-test
    :refer [post-file
            get-action
            get-res-status
            get-message-delivery-status
            get-attachment-copy-status]]
   ;[sm_async_api.dal.user :as dal-u]
   [sm_async_api.dal.globals :as dal-g]
   ;[sm_async_api.session :as session]
   ;[sm_async_api.dal.configure :refer [execute-script]]
   ;[sm-async-api.task.sm_fake_resp :as SM]
   ;[sm_async_api.utils.base64 :refer [string->b64]]
   [taoensso.timbre :as timbre]
   [sm-async-api.hook.hook :as hook]
   [sm_async_api.utils.reflector :refer [;relector-set-responce
                                         reflector-start
                                         reflector-stop]]
   [sm-async-api.core :refer [startup shutdown]]))

(def basic-auth ["max" "Shis!!9SD234ds123"])

(def headers {"content-type"  "application/json"})
(def headers_png {"content-type"  "image/png"
                  "content-disposition" "attachment;filename=obsluzhivanie.png"})

(def action-url  "http://localhost:3000/")

(def interaction-id "SD1668017")

(def service "interactions")

(def action "AddHistoryRec")


(defmacro CreateActonUrl []
  `(str action-url service "/" interaction-id "/action/" action))

(defn insert_tag [tag] (str hook/tag-border tag hook/tag-border))

(def hook-parametric
  {:name "hook-parametric"
   :headers (str "{\"Content-Type\":\"application/json\","
                 "\"Connection\": \"keep-alive\""
                 ;",\"Authorization\":\"" (insert_tag "REQ_ID")  "\""
                 "}")
   :user_name "0"
   :method "post"
   :url (str "http://localhost:13080/SM/9/rest/webresponce?user-name=" (insert_tag "USER_NAME"))
   :body (str "{\"RC\":" (insert_tag "RC") ","
              "\"MS\": " (insert_tag "MS") ","
              "\"REQ_ID\":\"" (insert_tag "REQ_ID")  "\","
              "\"STATUS\": " (insert_tag "STATUS") ","
              "\"COPY_REPORT\": " (insert_tag "COPY_REPORT") ","
              "\"FullBody\":" (insert_tag "BODY")  "}")

   :max_retries  3
   :retry_interval 11})

(def parameters_only_body
  (json/generate-string {:parameters
                         {:Interaction
                          {:Description ["New info from user"
                                         "New info from line 2"]}}}))

(def parameters_waiting
  (json/generate-string {:tag "hook-parametric"
                         :status "W"
                         :parameters
                         {:Interaction
                          {:Description ["New info from user"
                                         "New info from line 3"]}}}))


(deftest test-post-action
  (with-local-vars [rec_id nil]
    (testing "Phase 1: post action"
      (let [{:keys [status  body]}
            @(http/post  (CreateActonUrl) #_(str action-url "interactions/SD1668017/action/AddHistoryRec")
                         {:basic-auth basic-auth
                          :headers headers
                          :body parameters_only_body})]
        (when (not (nil? body))
          (var-set rec_id (:rec_id (json/parse-string body keyword))))
        (is (= 200 status))
        (is (some? @rec_id))))
    (when (some? @rec_id)
      (Thread/sleep 20000)
      (testing (str "Phase 2: get result for " @rec_id)
        (if (nil? @rec_id)
          (is false)
          (let [{:keys [status  body]}
                @(http/get (str action-url "Action/" @rec_id "/result")
                           {:basic-auth basic-auth
                            :headers headers
                            :body ""})]
            (is (= 200 status))
            (is (not (nil? body)))
            (is (= 0 (:ReturnCode (json/parse-string body keyword))))))))))


(deftest test-post-enriched-action-sync
  (with-local-vars [rec_id nil]
    (testing "Phase 1: post action"
      (let [{:keys [status  body]}
            @(http/post (CreateActonUrl) #_(str action-url "interactions/SD1668017/action/AddHistoryRec")
                        {:basic-auth basic-auth
                         :headers headers
                         :body parameters_waiting})]
        (when (not (nil? body))
          (var-set rec_id (:rec_id (json/parse-string body keyword))))
        (is (= 200 status))
        (is (not (nil? @rec_id)))))
    (testing (str "Phase 2.1: post first file into" @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status]}
              (post-file @rec_id "MP900216006.JPG" "image/jpg" basic-auth)]
          (is (= 200 status)))))
    (testing (str "Phase 2.2: post second file into" @rec_id)
      (if (nil? @rec_id)
        (is false)
        (let [{:keys [status]}
              (post-file @rec_id "obsluzhivanie.png" "image/png" basic-auth)]
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
                     (get-action basic-auth)
                     get-res-status)))
      (is (= 200 (get-message-delivery-status  @rec_id))))
    (testing (str "Phase 5: check attachment copy " @rec_id)
      (is (= true  (get-attachment-copy-status @rec_id '(200)))))))



(defn fix-test-core [t]
  (timbre/set-level! :info)
  (startup  {:-port 3000 :-path "test/config/integration/" :-db-clean true})
  ((@dal-g/hook-action :add-template)  hook-parametric)
  (reflector-start)
  (t)
  (reflector-stop)
  (shutdown))



(use-fixtures :once fix-test-core)