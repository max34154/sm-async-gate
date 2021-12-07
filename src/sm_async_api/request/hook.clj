(ns sm-async-api.request.hook
  (:require   [cheshire.core :as json]
              [sm_async_api.http_errors :as http-errors]
              [sm_async_api.dal.globals :refer [hook-action]]
              [sm-async-api.hook.hook :as hook]
              [sm_async_api.config :as config]
              [taoensso.timbre :as timbre :refer [error debug]])

  (:import [java.sql SQLIntegrityConstraintViolationException BatchUpdateException]))

(defn update-or-insert-hook-factory []
  (let [update-or-insert-hook (@hook-action :update-or-insert-template)]
    (fn [{:keys [body user_name] :as req}]
      (try
        (update-or-insert-hook (assoc (json/parse-string (slurp body) true) :user_name user_name))
        http-errors/ok-200
        (catch  SQLIntegrityConstraintViolationException e  (error (str (ex-message e) "\nRequest " req))
                (http-errors/internal-50x
                 " Database error."))
        (catch  BatchUpdateException e  (error (str (ex-message e) "\nRequest " req))
                (http-errors/internal-50x
                 " Database error."))
        (catch  AssertionError e (error (ex-message e))
                (http-errors/validation-error-406
                 (http-errors/friendly-assertion-errors e)))))))

(defn delete-hook-factory []
  (let [delete-hook (@hook-action :delete-template)]
    (fn [{:keys [route-params user_name]}]
      (if (zero? (first (delete-hook (:action_id route-params) user_name)))
        (http-errors/not-found-404)
        http-errors/ok-200))))

(defn get-hook-factory []
  (let [get-hook (@hook-action :get-template)]
    (fn [{:keys [route-params user_name]}]
      (let [result (first (get-hook {:tag (:action_id route-params)
                                     :user-name user_name}))]
        (if (nil? result)
          (http-errors/not-found-404)
          (http-errors/single-result-200 {:Hook result}))))))

(defn hook-factory []
  (let [delete-hook (delete-hook-factory)
        get-hook (get-hook-factory)
        module-ip  (config/get-config :module_ip)]
    (fn [req]
      (case  (:request-method req)
        :delete (delete-hook req)
        :get  (get-hook req)
        (http-errors/not-found-404 (str "Unsupported REST command" module-ip))))))


(defn get-all-hooks-factory []
  (let [get-all-hooks (@hook-action :get-all-available-templates)]
    (fn [{:keys [user_name]}]
      (let [result (get-all-hooks user_name)
            cnt (count result)]
        {:status 200
         :headers {"content-type" "application/json"}
         :body  (json/generate-string
                 {"@count" cnt
                  "@start" 1
                  "@totalcount" cnt
                  "ReturnCode" 0
                  "ResourceName" "Hook"
                  :content result})}))))

(defn test-hook-factory [mode]
  (let [get-hook (@hook-action :get-template)]
    (fn [req]
      (let [hook-template (first (get-hook  {:tag (-> req :route-params :action_id)
                                             :user-name (:user_name req)}))]
        (if (nil? hook-template)
          (http-errors/not-found-404)
          (do
            (when (= mode :test-run)  (hook/test-run-message  req))
            (http-errors/single-result-200 {:Hook (hook/preview-message req  hook-template)})))))))
