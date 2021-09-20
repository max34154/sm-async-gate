(ns sm_async_api.task.writers
  (:require [cheshire.core :as json]
            [sm_async_api.dal :as dal]
            [sm_async_api.config :as config]))

;(defonce result-writer  (agent {}))

;(defonce action-rescheduler  (agent {}))

(defn get-result-writer [_]
  (if (= (config/get-config :write-intermidiate-result) true)
    (fn [rec-id body status] (try
                               (dal/update-task-result rec-id body status 't')
                               (catch Exception e
                                 (println (ex-message e))
                                 (ex-message e))))
    (fn [rec-id body status] (try (dal/add-task-result rec-id body status)
                                   (catch Exception e
                                     (println (ex-message e))
                                     (ex-message e))))))

(defn get-action-rescheduler [_]
  (if (= (config/get-config :write-intermidiate-result) true)
    (fn [rec-id body status] (try
                               (dal/update-task-result-and-reschedule rec-id body status)
                               (catch Exception e
                                 (println (ex-message e))
                                 (ex-message e))))
    (fn [rec-id _ _]       (try
                             (dal/reschedule_task rec-id)
                             (catch Exception e
                               (println (ex-message e))
                               (ex-message e))))))

(def result-writer (delay (get-result-writer nil)))

(def action-rescheduler (delay (get-action-rescheduler nil)))

(defn silent-result-writer [_ _ _])

(defn silent-action-rescheduler [_ _ _])

(defn result-writer-old [rec-id body status]
  (let [{:keys [Thread Seq]}
        (get (json/parse-string ((json/parse-string body) "user") "thread_info")  keyword)]
    (println  "Std Write rec-id=" rec-id  "thread=" Thread "seq=" Seq "state=" status)
    ;(Thread/sleep 5)
    ))

(defn action-reschedulerold [rec-id body status]
  (let [{:keys [Thread Seq]}
        (get (json/parse-string ((json/parse-string body) "user") "thread_info")  keyword)]
    (println  "Std Reschedule if possible rec-id=" rec-id  "thread=" Thread "seq=" Seq "state=" status)))