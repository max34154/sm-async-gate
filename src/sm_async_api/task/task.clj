(ns sm_async_api.task.task 
  {:clj-kondo/config  '{:linters {:unused-referred-var
                                  {:exclude {taoensso.timbre [log  trace  debug  info  warn  error  fatal  report
                                                              logf tracef debugf infof warnf errorf fatalf reportf
                                                              spy get-env]}}}}}
    (:require [taoensso.timbre :as timbre
                  :refer [log  trace  debug  info  warn  error  fatal  report
                logf tracef debugf infof warnf errorf fatalf reportf
                spy get-env]]
                [org.httpkit.server :as httpkit]
                ;[bidi.bidi :as bidi]
                [clojure.string :as str]
                ;[cheshire.core :as json]
                [bidi.ring :refer (make-handler)]
                [sm_async_api.http_errors :as http-errors]
                [sm_async_api.dal :as dal]
                [sm_async_api.attachment :as attachment]
                ;[ring.middleware.json :refer [wrap-json-body]]
                ;[ring.middleware.format :refer [wrap-restful-format]]
                [ring.middleware.params :refer [wrap-params]]
                [clojure.spec.alpha :as s ]))
            
(def no-tasks-available 
    {:status 200
    :headers {"content-type" "application/json"}
    :body  "{\"Messages\":[\"No tasks available for specified schedule name\"], \"ReturnCode\":9}"})

(def incorrect-task-request
    {:status 422
    :headers {"content-type" "application/json"}
    :body  "{\"Messages\":[\"Incorrect request, check worker and chunk_size fields\"], \"ReturnCode\":71}"})

(def incorrect-result
        {:status 422
        :headers {"content-type" "application/json"}
        :body  "{\"Messages\":[\"Incorrect post, check request id, content-type and body\"], \"ReturnCode\":71}"})

(defmacro tasks [^String tasks-list ]
    `{
        :status  200
        :headers {"content-type" "application/json"}
        :body (str  "{\"ReturnCode\": 0, \"Task\":" ~tasks-list "}")})

(defn get-uri-key [req key ]
    (when-let [ val ((str/split  (:uri req) #"\/" 3) 1) ]
        (assoc req  key val)))

(defn ok [ _ ]  http-errors/ok-200)

(defn warp-get-rec-id [handler]
    (fn [request]
        (handler (get-uri-key request :rec_id))))


(defn add-param [  key val buffer]
    (if (nil? val ) buffer (assoc buffer key val)))


#_{:clj-kondo/ignore [:unused-binding]}
(defn safe-parse-int [val] 
   (if (nil? val) nil (try (Integer/parseInt  val)
                           (catch Exception ex  nil))))

(defn convert-query-params [req ]
    (assoc req :query-params 
        (add-param :schedule  ((req :query-params) "schedule")
            (add-param  :chunk-size (safe-parse-int  ((req :query-params) "chunk-size")) {}))))

(defn warp-convert-query-params [handler]
  (fn [request] 
    (handler (convert-query-params request))))

(defn write_result [ req ] 
    
    (if (s/valid?  :sm_async_api.validate/post-task-result (spy :debug req) )
      (let [result (dal/wrap-insert dal/post_task_result req)]
        (if  (nil? (:err result))
           http-errors/ok-200 
          (http-errors/internal-50x  req   result)))
    incorrect-result))

(defn get_chunk [ request ] 
    (let [ req (convert-query-params (get-uri-key request :worker))] 
       (if (s/valid?  :sm_async_api.validate/get-task-request (spy :debug req))
        (let [ {:keys [worker query-params]} req 
               {:keys [schedule chunk-size]} query-params ]
           (if (= ((dal/lock_tasks worker schedule chunk-size ) 0) 0)
             no-tasks-available 
             (tasks (dal/get_tasks worker))))        
        incorrect-task-request)))

(defn post_result [req]
        ((-> write_result
             ;(wrap-json-body {:keywords? true :bigdecimals? false})
             warp-get-rec-id) req))
;(defn set-rec_id [req]
;            (assoc req :rec_id (generate-unique-id)))
          
;(defn warp-set-rec_id [handler]
;            (fn [request]
;              (handler (set-rec_id request))))

  

              
(defn warp-debug-req [handler]
                (fn [request]
                  (debug "Body " (request :body))
                  (handler (spy :debug request))))

(def worker-routes ["/"  { [:worker "/lock"] {:put get_chunk} 
                        [:action_id "/result" ] {:post post_result}
                        [:action_id "/attachment"]  {:get attachment/get-attachments-list}  
                        [:action_id "/attachment/" :attachment_id] {:get attachment/get-attachment}}]  ) 
                        
(def worker-app (-> worker-routes
                            (make-handler)
                            (warp-debug-req)
                            (wrap-params)
                            ;(warp-get-uri-key)
                            ;(wrap-restful-format :formats [:edn :json])
                            ))
                        
(defn worker-access [port]
        (httpkit/run-server (var worker-app) {:port port})
        (info (format "Access for workers provided at port %d..." port)))
