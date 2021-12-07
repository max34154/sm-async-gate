(ns sm_async_api.utils.sm_resp_decode
  (:require
   [cheshire.core :as json]
   [clojure.string :as s]
   [taoensso.timbre :as timbre
    :refer [errorf]]))

(defn get-jbody [body headers]
  (when-let [content-type (:content-type headers)]
   (when (s/includes? content-type "application/json")
     (try (json/parse-string body)
          (catch Exception e
            (errorf "Error %s on parsing json %s "  (ex-message e) body))))))

(defmacro get-RC [body headers]
  `(get (get-jbody ~body ~headers) "ReturnCode"))

