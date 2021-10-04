(ns sm_async_api.attachment 
  (:require
   [sm_async_api.dal :as dal]
   [clojure.string :as str]
   [sm_async_api.http_errors :as http-errors]
   [cheshire.core :as json])
   (:import [java.sql SQLIntegrityConstraintViolationException])
   (:import [java.net URLEncoder]))

(defn write-attachment [req]
  ;(debug  "Write attachment" req)
  (try
    {:status 200
     :headers {"content-type" "application/json"}
     :body  (json/generate-string
             (let [content-type (req :content-type)]
               (if (nil? content-type)
                 (throw (AssertionError. "empty ct"))
                 (if (str/includes? content-type  "multipart/form-data")
                   (dal/insert_multypart_attachment req)
                   (dal/insert_attachment req)))))}
    (catch  SQLIntegrityConstraintViolationException e  (ex-message e)
            (http-errors/validation-error-406
             (str "Action " (-> req :route-params :action_id) " does not exist.")))
    (catch  AssertionError e  (ex-message e)
            (http-errors/validation-error-406
             (http-errors/friendly-assertion-errors e)))))

(defn  get-attachments-list [req]
  {:status 200
   :headers {"content-type" "application/js on"}
   :body (json/generate-string (dal/get_attachments_list req))})

(defn get-attachment [req]
  (let [{:keys [content_type,  name, body]}  (first (dal/get_attachment req))]
    (if (nil? body) (http-errors/not-found-404)
        {:status 200
         :headers {"Content-Type" content_type
                   "Content-Disposition" (str  "attachment;filename*=UTF-8''" (URLEncoder/encode ^String name "UTF-8"))}
         :body body})))