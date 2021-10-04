(ns sm_async_api.dal.attachment
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [sm_async_api.validate]
            [clojure.string :as str]
            [sm_async_api.config :as config]
            [clojure.java.io :as io]
            [sm_async_api.dal.globals :as g :refer [db]]
            [sm_async_api.utils.macro :refer [unixtime->timestamp tod]]
            [taoensso.timbre :as timbre
             :refer [;log  trace  debug  info  warn    fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf
                     ;spy get-env
                     error]]
            #_[taoensso.timbre.appenders.core :as appenders]))

(defn- file->bytes [file]
  (with-open [xin (io/input-stream file)
              xout (java.io.ByteArrayOutputStream.)]
    (io/copy xin xout)
    (.toByteArray xout)))

(defn blob-to-byte [blob]
  (let [ary (byte-array ^Integer (.length blob))
        is (.getBinaryStream blob)]
    (.read is ary)
    (.close is)
    ary))


(defn insert-attachment-factory [{:keys [db-schema]}]
  (let [attachment (keyword (str db-schema ".ATTACHMENT"))]
    (fn [req] {:pre [(s/valid? :sm_async_api.validate/insert-attachment req)]}
      (let [{:keys [route-params  content-type content-length headers body]} req
            att_id (config/get-uid)
            rec_id  (:action_id route-params)
            name ((str/split
                   ((str/split (headers "content-disposition") #";" 3) 1)  ; cut out filename=xx content despostion string 
                   #"=" 2) 1)]
        (jdbc/insert! @db attachment
                      {:att_id att_id
                       :att_req_id rec_id
                       :name  name
                       :content_type content-type
                       :body (file->bytes body)
                       :size content-length})
        {:href att_id}))))

(defn insert-multypart-attachment-factory [db-config]
  (let [insert-attachment (insert-attachment-factory db-config)]
    (fn [req]
      (let [mp  (req :multipart-params)
            req_id  (-> req :route-params :action_id)]
        (for [file mp]
          (let [{:keys [filename content-type tempfile size]} (file 1)
                att_id (config/get-uid)]
            (try
              (insert-attachment att_id req_id filename content-type tempfile size)
              {:name (file 0)
               :filename filename
               :href att_id}
              (catch AssertionError e  (error (ex-message e))
                     {:name (file 0)
                      :filename filename
                      :err e}))))))))

(defn get-attachments-list-factory [{:keys [db-schema]}]
  (let [attachment  (str db-schema ".ATTACHMENT")]
    (fn [req]
      (jdbc/query @db  [(str "SELECT ATT_ID as href, NAME, CONTENT_TYPE, SIZE FROM "
                             attachment
                             " WHERE ATT_REQ_ID=?")
                        (-> req :route-params :action_id)]))))



(defn get-attachment-factory [{:keys [db-schema]}]
  (let [attachment  (str db-schema ".ATTACHMENT")]
    (fn [req]
      (jdbc/query @db
                  [(str
                    "SELECT NAME, CONTENT_TYPE, SIZE, BODY FROM "
                    attachment
                    " WHERE ATT_ID= ? AND ATT_REQ_ID= ?") (-> req :route-params :attachment_id)
                   (-> req :route-params :action_id)]
                  {:row-fn #(assoc % :body (->> % :body blob-to-byte io/input-stream))}))))


(defn get-attachments-ids-by-req-id-factory [{:keys [db-schema]}]
  (let [attachment  (str db-schema ".ATTACHMENT")]
    (fn [^String id]
      (jdbc/query @db  [(str "SELECT ATT_ID" attachment " WHERE ATT_REQ_ID=?") id]))))

(defn get-attachments-by-req-id-factory [{:keys [db-schema]}]
  (let [attachment  (str db-schema ".ATTACHMENT")]
    (fn [^String id]
      (jdbc/query @db
                  [(str
                    "SELECT NAME, CONTENT_TYPE, SIZE, BODY, STATUS FROM "
                    attachment
                    " WHERE ATT_REQ_ID= ?") id]
                  {:row-fn #(assoc % :body (->> % :body blob-to-byte io/input-stream))}))))

(defn get-attachment-body-by-id-factory [{:keys [db-schema]}]
  (let [attachment  (str db-schema ".ATTACHMENT")]
    (fn [^String id]
      (jdbc/query @db
                  [(str "SELECT BODY FROM "  attachment  " WHERE ATT_ID= ?") id]
                  {:row-fn #(assoc % :body (->> % :body blob-to-byte io/input-stream))}))))

(defn set-attachment-copy-mark-factory [{:keys [db-schema]}]
  (let [attachment  (str db-schema ".ATTACHMENT")]
    (fn [^String id ^String status]
      (jdbc/update! @db attachment {:status status :cp-time (unixtime->timestamp (tod))} ["ATT_ID=?" id]))))