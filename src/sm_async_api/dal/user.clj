(ns sm_async_api.dal.user
  (:require [clojure.java.jdbc :as jdbc]
            ;[clojure.spec.alpha :as s]
             ;[taoensso.timbre.appenders.core :as appenders]
            [sm_async_api.validate]
            ;[sm_async_api.config :as config]
            [sm_async_api.utils.crypto :as crypto :refer [encrypt decrypt]]
            [sm_async_api.dal.globals :as g :refer [db user-action]]
            [sm_async_api.utils.macro :refer [unixtime->timestamp]]
            #_[taoensso.timbre :as timbre
               :refer [;log  trace  debug  info  warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf
                     ;spy get-env
                       errorf]]))


(defn- encrypt-row [{:keys [password name expire_at]}]
  (let [{:keys [data iv]} (encrypt password name)]
    {:name name :password data :toc iv :expire_at expire_at}))

(defn- decrypt-row [{:keys [name password toc expire_at]}]
  {:name name :val {:name name
                    :password (decrypt {:data password :iv toc} name)
                    :expire_at expire_at}})

(defn update-user-factory [^String db-schema]
  (let [user-cache (keyword (str db-schema ".user"))]
    (fn [row]
      (let [row (->> row
                     encrypt-row
                     (#(when-let [exp  (:expire_at %)]
                         (assoc % :expire_at
                                (unixtime->timestamp (* 1000 exp))))))]
        (jdbc/with-db-transaction [t-con @db]
          (let [result (jdbc/update! t-con user-cache row ["name=?" (row :name)])]
            (if (zero? (first result))
              (jdbc/insert! t-con user-cache row)
              result)))))))


(defn get-all-user-factory [^String db-schema]
  (let [user-cache  (keyword (str db-schema ".user"))]
    (fn  []
      (reduce #(let [{:keys [name val]}  %2] (assoc %1 name val)) {}
              (jdbc/query @db  user-cache ;[(str  "SELECT * FROM " user-cache)]
                          {:row-fn decrypt-row})))))

(defn get-user-factory [^String db-schema]
  (let [user-cache  (keyword (str db-schema ".user"))]
    (fn [name]
      (first (jdbc/query @db user-cache ["name=?" name] ; [(str  "SELECT * FROM " user-cache " WHERE name='" name "'")]
                         {:row-fn decrypt-row})))))

(defn delete-user-factory [^String db-schema]
  (let [user-cache  (keyword (str db-schema ".user"))]
    (fn [name]
      (jdbc/delete! @db  user-cache ["name=?" name]))))

(defn delete-user-cache-factory [^String db-schema]
  (let [user-cache  (keyword (str db-schema ".user"))]
    (fn []
      (jdbc/delete! @db  user-cache ))))

(defn configure [ {:keys [^String db-schema]}]
  {:update (update-user-factory db-schema)
   :get-all (get-all-user-factory db-schema)
   :get-by-name (get-user-factory db-schema)
   :delete-by-name (delete-user-factory db-schema)
   :delete-user-cache (delete-user-cache-factory db-schema)})

(def get-user (delay (@user-action :get-by-name)))

(def update-user (delay (@user-action :update)))

(def delete-user (delay (@user-action :delete-by-name)))