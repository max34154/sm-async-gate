(ns sm_async_api.validate
  (:require [clojure.spec.alpha :as s]
            [sm_async_api.config :as config]))

(create-ns 'action-request)

(def execution-mode-list {"I" true "IS" true "S" true})



(def max-retries 101)

(def min-retry-interval 9)

(def max-chunk-size 51)


(s/def :action-request/execution_mode execution-mode-list)

(s/def :action-request/status  #(or (= "N" ^String %) (= "W" ^String %)))

(s/def :action-request/execution_retries  (s/and int? #(> % -1) #(> max-retries %)))

(s/def :action-request/retry_interval  (s/and int? #(> % min-retry-interval)))

(s/def :action-request/body (s/or :nil nil? 
                                  :json (s/keys  :opt-un [:action-request/execution_mode
                                              :action-request/execution_retries
                                              :action-request/status
                                              :action-request/retry_interval])))

(s/def ::post-action-request (s/keys :opt-un [:action-request/body]))

(create-ns 'task-request)

(s/def :task-request/worker (s/and string? not-empty))

(s/def :task-request/rec_id (s/and string? not-empty))

(s/def :task-request/content-type (s/and string? #(= "application/json" ^String %)))

;(s/def :task-request/body (s/and string? not-empty))
(s/def :task-request/body   some? )

(s/def :task-request/chunk-size (s/and int? #(> % 0) #(> max-chunk-size %)))

(s/def :task-request/query-params (s/keys :opt-un [:task-request/chunk-size]))

(s/def ::get-task-request (s/keys :req-un [:task-request/worker]
                                  :opt-un [:task-request/query-params]))

(s/def ::post-task-result (s/keys :req-un [:task-request/rec_id
                                           :task-request/content-type
                                           :task-request/body]))
(create-ns 'attachment)

(def ^:private mem-types (delay (config/get-config :mime-types)))

(def ^:private max-attachment-size (delay (config/get-config :max-attachment-size)))

(s/def :attachment/content-type  #( @mem-types (keyword %)))

(s/def :attachment/content-length (s/and int? #(> @max-attachment-size  %)))

(s/def ::insert-attachment (s/keys :req-un[:attachment/content-type
                                           :attachment/content-length]))