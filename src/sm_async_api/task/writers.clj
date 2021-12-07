(ns sm_async_api.task.writers
  (:require [cheshire.core :as json]
            [sm_async_api.dal.globals :refer  [task-action]]
            [sm_async_api.config :as config]))


(defonce silent-mode (atom false))

;(defonce result-writer  (agent {}))

;(defonce action-rescheduler  (agent {}))

(defn no-db-writes  []
  reset!  silent-mode  true)

(defn get-result-writer [_]
  (let [update-task-result (@task-action :update-result)
        add-task-result (@task-action :add-result)]
    (if @silent-mode
      (fn [_ _ _]) ; avoid any db activity for tests only 
      (if (= (config/get-config :write-intermidiate-result) true)
        (fn [rec-id body status] (try
                                   (update-task-result rec-id body status 't')
                                   (catch Exception e
                                     (println (ex-message e))
                                     (ex-message e))))
        (fn [rec-id body status] (try (add-task-result rec-id body status)
                                      (catch Exception e
                                        (println (ex-message e))
                                        (ex-message e))))))))

(defn get-action-rescheduler [_]
  (let [update-task-result-and-reschedule (@task-action :update-result-and-reschedule)
        reschedule_task (@task-action :reschedule)]
    (if @silent-mode
      (fn [_ _ _])
      (if (= (config/get-config :write-intermidiate-result) true)
        (fn [rec-id body status] (try
                                   (update-task-result-and-reschedule rec-id body status)
                                   (catch Exception e
                                     (println (ex-message e))
                                     (ex-message e))))
        (fn [rec-id _ _]       (try
                                 (reschedule_task rec-id)
                                 (catch Exception e
                                   (println (ex-message e))
                                   (ex-message e))))))))

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