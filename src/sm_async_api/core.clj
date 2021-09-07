(ns sm-async-api.core
  (:require ;[clojure.java.io :as io]
            ;[clojure.pprint :refer [pprint]]
            [org.httpkit.server :as httpkit]
            ;[ring.util.http-response :refer [ok content-type ] :as resp]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.content-type :refer [wrap-content-type]]
           ; [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            ;[bidi.bidi :as bidi]
            [bidi.ring :refer (make-handler)]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ring.middleware.json :refer [wrap-json-body]]
            [taoensso.timbre :as timbre
             :refer [
                     ;log  trace      warn  error  fatal  report
                     ;logf tracef debugf infof warnf errorf fatalf reportf get-env
                     spy debug info error ]]
            [sm_async_api.session :as session]
            [sm_async_api.dal :as dal]
            [sm_async_api.config :as config]
            [sm_async_api.task.task :as task :refer [worker-access]]
            [sm_async_api.http_errors :as http-errors])
  (:gen-class)
  (:import [java.sql SQLIntegrityConstraintViolationException])
  (:import [java.net URLEncoder]))



;(def module_name (atom "undefined"))

;(let [i (atom 0)]
;  (defn generate-unique-id []
;  (format "%X-%X-%s" (quot (System/currentTimeMillis) 1000) (swap! i inc)
            ;@config/module_name 
;            (config/get-module-name))))

#_(defn serve-index [_]
  (-> "<html><h1>Hello</h1></html>"
      (ok)
      (content-type "text/html; charset=UTF-8")))

#_(defn serve-ping [req]
  (ok {:id (-> req :route-params :name)}))

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




(defn write_action [req]
  (debug  "Write action" (req :rec_id))
  (debug  "Write action" req)
  (let [result (dal/insert_action req)]
    (if  (vector?  (spy result))
      {:status 200
       :headers {"content-type" "application/json"}
       :body (json/generate-string    {:rec_id (req :rec_id)
                                       :ReturnCode 0})}
      (http-errors/internal-50x  req   result))))

(defn post-action [req]
  ((-> write_action
       warp-set-rec_id
       (wrap-json-body {:keywords? true :bigdecimals? false})
       (warp-set-service)) req))

(defn post-action-acl [req]
  ((-> write_action
       warp-set-rec_id
       (wrap-json-body {:keywords? true :bigdecimals? false})
       session/warp-check-acl
       (warp-set-service)) req))



(defmacro parse-select-responce [req result]
  `(if  (nil?  ~result)
     (http-errors/internal-50x  ~req 500)
     (http-errors/single-result-200 ~result)))


(defn get-action [req]
  (let [result (first (dal/get_action req))]
    (parse-select-responce req result)))

(defn get-action-result [req]
  (let [result (first (dal/get_result req))]
    (parse-select-responce req result)))

(defn cancel-action [req]
  (if (=  ((dal/cancel_action req) 0) 0)
    http-errors/no-results-200
    http-errors/ok-200))

(defn run-action [req]
  (if (=  ((dal/run_action req) 0) 0)
    http-errors/no-results-200
    http-errors/ok-200))


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
    (catch  SQLIntegrityConstraintViolationException e  (error (ex-message e))
            (http-errors/validation-error-406
             (str "Action " (-> req :route-params :action_id) " does not exist.")))
    (catch  AssertionError e (error (ex-message e))
            (http-errors/validation-error-406
             (http-errors/friendly-assertion-errors e)))))


(defn  add-attachment [req]
  ((-> write-attachment
       wrap-multipart-params) req))

(defn  get-attachments-list [req]
  {:status 200
   :headers {"content-type" "application/js on"}
   :body (json/generate-string (dal/get_attachments_list req))})

(defn attachments [req]
  (case  (req :request-method)
    :post (add-attachment req)
    :get  (get-attachments-list req)
    (http-errors/not-found-404 (str "Unsupported REST command" (config/get-config :module_ip))))) ;(get cconfig/config :module_ip)


(defn get-attachment [req]
  (let [{:keys [content_type, _, name, body]}  (first (dal/get_attachment req))]
    (if (nil? body) (http-errors/not-found-404)
        {:status 200
         :headers {"Content-Type" content_type
                   "Content-Disposition" (str  "attachment;filename*=UTF-8''" (URLEncoder/encode name "UTF-8"))}
         :body body})))

#_(defn getb [] (get-attachment
                 {:route-params {:attachment_id "ATT-60FFF063-1-1AEF" :action_id "60F957F5-6-1AEF"}}))

(defn build_route [rdesc]
  (conj [[(str (get rdesc :name) "/") :name "/" :action_id]]
        (if (get rdesc :acl) {:post post-action-acl} {:post post-action})))

(defn debug-print-request [req]
  (println "Debug - " req)
  http-errors/ok-200)

(defn routes [collections]
  (vector "/"
          (into {["Action/" :action_id] {:get get-action}
                 ["Action/" :action_id "/result"] {:get get-action-result}
                 ["Action/" :action_id "/cancel"] {:put cancel-action}
                 ["Action/" :action_id "/attachments"]  attachments
                 ["Action/" :action_id "/run"] {:put run-action}
                 ["Action/" :action_id "/" :attachment_id] {:get get-attachment}
                 ["Debug/"] debug-print-request}

                (for [route  collections] ;(get cconfig/config :collections)
                  (build_route route)))))

(defn app []  (-> (routes (config/get-config :collections))
             (make-handler)
             (wrap-content-type  mime-types)
             (wrap-restful-format :formats [:edn :json])
             (session/warp-auth)))



(defonce AccesHTTPServer (atom nil))

(defn -main [& [port path]]
  (let [port (Integer/parseInt (or port "8080") 10)]
    (info "Read configuration")
    (config/configure path)
    (reset! AccesHTTPServer (httpkit/run-server (app) {:port port}));(var app)
    (info (format "App running at port %d..." port))
    (info "Module IP " (config/get-config :module_ip)) ;(get cconfig/config :module_ip)
    ;(when-let [g  (config/get-config :async_gateway_id)] ;(reset! module_name g)
    ;  (config/set-module-name g))
    (dal/check_db)
    (info "Configured collections: ")
    (doseq [collection (config/get-config :collections)]
      (let [{:keys [name acl]} collection]
        (info "Service" name  (if acl "access-list enabled" ""))))
    (worker-access  (+ port 1))))

(defn stop-AccesHTTPServer []
  (when-not (nil? @AccesHTTPServer)
    ;; graceful shutdown: wait 100ms for existing requests to be finished
    ;; :timeout is optional, when no timeout, stop immediately
    (@AccesHTTPServer :timeout 100)
    (reset! AccesHTTPServer nil)))


(comment (-main "3000"))