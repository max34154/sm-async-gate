(ns sm_async_api.task.task
  (:require [taoensso.timbre :as timbre
             :refer [debug info error spy]]
            [org.httpkit.server :as httpkit]
            [clojure.string :as str]
            [bidi.ring :refer (make-handler)]
            [sm_async_api.http_errors :as http-errors]
            [sm_async_api.config :refer [get-config]]
            [sm_async_api.dal.globals :refer [task-action]]
            [sm_async_api.attachment :as attachment]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.spec.alpha :as s])
  (:import [java.sql SQLException BatchUpdateException]))

(def ^:private no-tasks-available
  {:status 200
   :headers {"content-type" "application/json"}
   :body  "{\"Messages\":[\"No tasks available for specified schedule name\"], \"ReturnCode\":9}"})

(def ^:private incorrect-task-request
  {:status 422
   :headers {"content-type" "application/json"}
   :body  "{\"Messages\":[\"Incorrect request, check worker and chunk_size fields\"], \"ReturnCode\":71}"})

(def ^:private incorrect-result
  {:status 422
   :headers {"content-type" "application/json"}
   :body  "{\"Messages\":[\"Incorrect post, check request id, content-type and body\"], \"ReturnCode\":71}"})

(defmacro ^:private tasks [^String tasks-list]
  `{:status  200
    :headers {"content-type" "application/json"}
    :body (str  "{\"ReturnCode\": 0, \"Task\":" ~tasks-list "}")})

(defn- get-uri-key [req key]
  (when-let [val ((str/split  (:uri req) #"\/" 3) 1)]
    (assoc req  key val)))

(defn- ok [_]  http-errors/ok-200)

(defn- warp-get-rec-id [handler]
  (fn [request]
    (handler (get-uri-key request :rec_id))))


(defn- add-param [key val buffer]
  (if (nil? val) buffer (assoc buffer key val)))


#_{:clj-kondo/ignore [:unused-binding]}
(defn- safe-parse-int [val]
  (if (nil? val) nil (try (Integer/parseInt  val)
                          (catch Exception ex  nil))))

(defn- convert-query-params [req]
  (assoc req :query-params
         (add-param :schedule  ((req :query-params) "schedule")
                    (add-param  :chunk-size (safe-parse-int  ((req :query-params) "chunk-size")) {}))))

(defn- warp-convert-query-params [handler]
  (fn [request]
    (handler (convert-query-params request))))

(defn- wrap-insert [action req]
  (try (action req)
       
       (catch SQLException e (error (ex-message e))
              {:err "Can't insert action into DB."})
       (catch BatchUpdateException e (error (ex-message e))
              {:err "Can't insert action into DB."})
       (catch  AssertionError e (error (ex-message e))
               {:err "Incorrect action options" :status 422})))

(defn- write-result-factory []
  (let [post-task-result  (@task-action :post-resp)]
    (fn [req]
      (if (s/valid?  :sm_async_api.validate/post-task-result (spy :debug req))
        (let [result (wrap-insert post-task-result req)]
          (if  (nil? (:err result))
            http-errors/ok-200
            (http-errors/internal-50x  req   result)))
        incorrect-result))))

(defn- get-chunk-factory  []
  (let [lock-task (@task-action :lock)
        get-task (@task-action :get)]
    (fn [request]
      (let [req (convert-query-params (get-uri-key request :worker))]
        (if (s/valid?  :sm_async_api.validate/get-task-request (spy :debug req))
          (let [{:keys [worker query-params]} req
                {:keys [schedule chunk-size]} query-params]
            (if (= ((lock-task worker schedule chunk-size) 0) 0)
              no-tasks-available
              (tasks (get-task worker))))
          incorrect-task-request)))))

(defn- post-result [req]
  ((-> (write-result-factory)
             ;(wrap-json-body {:keywords? true :bigdecimals? false})
       warp-get-rec-id) req))

(defn- warp-debug-req [handler]
  (fn [request]
    (debug "Body " (request :body))
    (handler (spy :debug request))))

(defn- worker-routes [] ["/"  {[:worker "/lock"] {:put (get-chunk-factory)}
                               [:action_id "/result"] {:post post-result}
                               [:action_id "/attachment"]  {:get attachment/get-attachments-list}
                               [:action_id "/attachment/" :attachment_id] {:get attachment/get-attachment}}])

(defn- worker-app []  (-> @(delay (worker-routes))
                        (make-handler)
                        (warp-debug-req)
                        (wrap-params)
                            ;(warp-get-uri-key)
                            ;(wrap-restful-format :formats [:edn :json])
                        ))

(defn- worker-access [port]
  (httpkit/run-server (var worker-app) {:port port})
  (info (format "Access for workers provided at port %d..." port)))


(defonce ^:private PollHTTPServer (atom nil))

(defn start-PollHTTPServer
  ([] (start-PollHTTPServer (get-config :poll-port)))
  ([port] 
   (when (true? (get-config :poll-enabled))
     (reset! PollHTTPServer (httpkit/run-server (worker-app) {:port port})))))

(defn stop-PollHTTPServer
  " Graceful shutdown access server: wait 100ms for existing requests to be finished
   timeout is optional, when no timeout, stop immediately"
  []
  (when-not (nil? @PollHTTPServer)
    (@PollHTTPServer :timeout 100)
    (reset! PollHTTPServer nil)))
