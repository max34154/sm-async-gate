(ns sm-async-api.request.request
  (:require    [org.httpkit.server :as httpkit]
               [ring.middleware.format :refer [wrap-restful-format]]
               [ring.middleware.content-type :refer [wrap-content-type]]
               [ring.middleware.multipart-params :refer [wrap-multipart-params]]
               [bidi.ring :refer (make-handler)]
               [clojure.string :as str]
               [cheshire.core :as json]
               [ring.middleware.json :refer [wrap-json-body]]
               [taoensso.timbre :as timbre
                :refer [;log  trace  spy  info  warn  error  fatal  report
           ;logf tracef debugf infof warnf errorf fatalf reportf get-env
                        debug  error]]
               [sm_async_api.session :as session]
               [sm_async_api.dal.request :as dal-r]
               [sm_async_api.dal.attachment :as dal-a]
               [sm_async_api.config :as config]
               [sm_async_api.http_errors :as http-errors])
  (:gen-class)
  (:import [java.sql SQLIntegrityConstraintViolationException])
  (:import [java.net URLEncoder]))

(defn set-service [req]
  (assoc req :service (keyword ((str/split  (:uri req) #"\/" 3) 1))))

(defn warp-set-service [handler]
  (fn [request]
    (handler (set-service request))))

(defn set-rec_id [req]
  (assoc req :rec_id (config/get-uid)))

(defn warp-set-rec_id [handler]
  (fn [request]
    (handler (set-rec_id request))))

(def mime-types {:mime-types {"json" "application/json" "png" "image/png"}})

; Пример заполненного запроса (req)
;  {:service :FOSpp, 
;   :remote-addr "0:0:0:0:0:0:0:1", 
;   :params {"schedule_name" "test_sch", 
;            "execution_mode" "S", 
;            "execution_retries" 65, 
;            "retry_interval" 305, 
;            "parameters" "something", 
;            :name "SD123232", 
;            :action_id "add"}, 
;   :body-params {"schedule_name" "test_sch",
;                 "execution_mode" "S", 
;                 "execution_retries" 65, 
;                 "retry_interval" 305, 
;                 "parameters" "something"}, 
;   :route-params {:name "SD123232", :action_id "add"}, 
;   :headers {"host" "localhost:3000", 
;             "user-agent" "PostmanRuntime/7.28.0", 
;             "content-type" "application/json", 
;             "content-length" "149", 
;             "connection" "keep-alive", 
;             "accept" "*/*", 
;             "authorization" "Basic XXXXXXXXXBase64", 
;             "accept-encoding" "gzip, deflate, br", 
;             "postman-token" "4fcbc510-8999-4707-81fb-5366b318851a", 
;             "cache-control" "no-cache"}, 
;   :async-channel #object[org.httpkit.server.AsyncChannel 0x5290f1ac "/[0:0:0:0:0:0:0:1]:3000<->/[0:0:0:0:0:0:0:1]:59730"], 
;   :server-port 3000, 
;   :rec_id "6112260A-1-1AEF", 
;   :content-length 149, 
;   :websocket? false, 
;   :content-type "application/json", 
;   :character-encoding "utf8",
;   :uri "/FOSpp/SD123232/add", 
;   :server-name "localhost", 
;   :user_name "max", 
;   :query-string nil, 
;   :body {:schedule_name "test_sch", 
;          :execution_mode "S", 
;          :execution_retries 65, 
;          :retry_interval 305, 
;          :parameters "something"}, 
;          :scheme :http, 
;          :request-method 
;          :post}




(defn write-action-factory []
  (let [insert-action (dal-r/insert-action-factory (:database @config/config))]
    (fn [req]
      (debug  "Write action" (req :rec_id))
      (let [result (dal-r/wrap-insert insert-action req)]
        (if  (nil? (:err  result))
          {:status 200
           :headers {"content-type" "application/json"}
           :body (json/generate-string    {:rec_id (req :rec_id)
                                           :ReturnCode 0})}
          (http-errors/internal-50x  req   result))))))

(defn post-action-factory []
  (let [write-action (write-action-factory)]
    (fn [req]
      ((-> write-action
           warp-set-rec_id
           (wrap-json-body {:keywords? true :bigdecimals? false})
           (warp-set-service)) req))))

(defn post-action-acl-factory []
  (let [write-action (write-action-factory)]
    (fn [req]
      ((-> write-action
           warp-set-rec_id
           (wrap-json-body {:keywords? true :bigdecimals? false})
           session/warp-check-acl
           (warp-set-service)) req))))

(defmacro parse-select-responce [req result]
  `(if  (nil?  ~result)
     (http-errors/internal-50x  ~req 500)
     (http-errors/single-result-200 ~result)))

(defn- get-action-factory []
  (let [get-action (dal-r/get-action-factory (:database @config/config))]
    (fn [req]
      (let [result (first (get-action req))]
        (parse-select-responce req result)))))

(defn- get-action-result-factory []
  (let [get-result (dal-r/get-result-factory (:database @config/config))]
    (fn [req]
      (let [result (first (get-result req))]
        (parse-select-responce req result)))))

(defn- cancel-action-factory []
  (let [cancel-action (dal-r/cancel-action-factory (:database @config/config))]
    (fn [req]
      (if (=  (first (cancel-action req)) 0)
        http-errors/no-results-200
        http-errors/ok-200))))

(defn run-action-factory []
  (let [run-action (dal-r/run-action-factory (:database @config/config))]
    (fn [req]
      (if (=  (first (run-action req)) 0)
        http-errors/no-results-200
        http-errors/ok-200))))


#_(comment (def req {:remote-addr "0:0:0:0:0:0:0:1"
                     :params {:action_id "60F957F5-6-1AEF"}
                     :route-params {:action_id "60F957F5-6-1AEF"}
                     :headers {"host" "localhost:3000"
                               "user-agent" "PostmanRuntime/7.28.0"
                               "content-type" "image/png"
                               "content-length" "31305"
                               "connection" "keep-alive"
                               "accept" "*/*", "authorization"
                               "Basic bWF4OlNoaXMhITlTRDIzNGRzMTIz"
                               "accept-encoding" "gzip, deflate, br"
                               "content-disposition" "attachment;filename=test.jpg"
                               "postman-token" "d9a36024-d0b8-4312-9197-d891cb24bd76"
                               "cache-control" "no-cache"}
          ;:async-channel #object[org.httpkit.server.AsyncChannel 0x784d951c "/[0:0:0:0:0:0:0:1]:3000<->/[0:0:0:0:0:0:0:1]:64727"], 
                     :server-port 3000
                     :content-length 31305
                     :websocket? false
                     :content-type "image/png"
                     :character-encoding "utf8"
                     :uri "/Action/60F957F5-6-1AEF/attachments"
                     :server-name "localhost"
                     :user_name "max"
                     :query-string nil}))

(defn write-attachment-factory []
  (let [insert-multypart-attachment (dal-a/insert-multypart-attachment-factory (:database @config/config))
        insert-attachment (dal-a/insert-attachment-factory (:database @config/config))]
    (fn  [req]
  ;(debug  "Write attachment" req)
      (try
        {:status 200
         :headers {"content-type" "application/json"}
         :body  (json/generate-string
                 (let [content-type (req :content-type)]
                   (if (nil? content-type)
                     (throw (AssertionError. "empty ct"))
                     (if (str/includes? content-type  "multipart/form-data")
                       (insert-multypart-attachment req)
                       (insert-attachment req)))))}
        (catch  SQLIntegrityConstraintViolationException e  (error (ex-message e))
                (http-errors/validation-error-406
                 (str "Action " (-> req :route-params :action_id) " does not exist.")))
        (catch  AssertionError e (error (ex-message e))
                (http-errors/validation-error-406
                 (http-errors/friendly-assertion-errors e)))))))

(defn  add-attachment-factory []
  (let [write-attachment (write-attachment-factory)]
    (fn  [req]
      ((-> write-attachment
           wrap-multipart-params) req))))

(defn  get-attachments-list-factory []
  (let [get-attachments-list (dal-a/get-attachments-list-factory (:database @config/config))]
    (fn [req]
      {:status 200
       :headers {"content-type" "application/js on"}
       :body (json/generate-string (get-attachments-list req))})))

(defn attachments-factory []
  (let [get-attachments-list (get-attachments-list-factory)
        add-attachment (add-attachment-factory)
        module-ip  (config/get-config :module_ip)]
    (fn  [req]
      (case  (req :request-method)
        :post (add-attachment req)
        :get  (get-attachments-list req)
        (http-errors/not-found-404 (str "Unsupported REST command" module-ip))))))


(defn get-attachment-factory []
  (let [get-attachment (dal-a/get-attachment-factory (:database @config/config))]
    (fn [req]
      (let [{:keys [content_type, _, name, body]}  (first (get-attachment req))]
        (if (nil? body) (http-errors/not-found-404)
            {:status 200
             :headers {"Content-Type" content_type
                       "Content-Disposition" (str  "attachment;filename*=UTF-8''" (URLEncoder/encode name "UTF-8"))}
             :body body})))))

(defn build_route [rdesc]
  (conj [[(str (get rdesc :name) "/") :name "/" :action_id]]
        (if (get rdesc :acl) {:post (post-action-acl-factory)} {:post (post-action-factory)})))

(defn debug-print-request [req]
  (println "Debug - " req)
  http-errors/ok-200)

(defn routes [collections]
  (vector "/"
          (into {["Action/" :action_id] {:get (get-action-factory)}
                 ["Action/" :action_id "/result"] {:get (get-action-result-factory)}
                 ["Action/" :action_id "/cancel"] {:put (cancel-action-factory)}
                 ["Action/" :action_id "/attachments"]  (attachments-factory)
                 ["Action/" :action_id "/run"] {:post (run-action-factory)}
                 ["Action/" :action_id "/" :attachment_id] {:get (get-attachment-factory)}
                 ["Debug/"] debug-print-request}

                (for [route  collections] ;(get cconfig/config :collections)
                  (build_route route)))))

(defn app []  (-> (routes (config/get-config :collections))
                  (make-handler)
                  (wrap-content-type  mime-types)
                  (wrap-restful-format :formats [:edn :json])
                  (session/warp-auth)))



(defonce AccesHTTPServer (atom nil))

(defn start-AccessHTTPServer
  ([] (start-AccessHTTPServer "8080"))
  ([port] (reset! AccesHTTPServer (httpkit/run-server (app) {:port port}))))

(defn stop-AccesHTTPServer
  " Graceful shutdown access server: wait 100ms for existing requests to be finished
   timeout is optional, when no timeout, stop immediately"
  []
  (when-not (nil? @AccesHTTPServer)

    (@AccesHTTPServer :timeout 100)
    (reset! AccesHTTPServer nil)))
