(ns sm_async_api.utils.reflector
  {:clj-kondo/config  '{:linters {:unused-referred-var
                                  {:exclude {taoensso.timbre [log  trace  debug  info  warn  error  fatal  report
                                                              logf tracef debugf infof warnf errorf fatalf reportf
                                                              spy get-env]

                                             sm-async-api.task.sm_fake_resp  [responce-OK
                                                                              responce-BAD-REQ
                                                                              responce-INTERNAL-ERROR-GENERIC
                                                                              responce-INTERNAL-ERROR
                                                                              responce-NOT-ATHORIZED
                                                                              responce-TOO-MANY-THREADS
                                                                              responce-NO-SERVER-json
                                                                              responce-NO-SERVER-no-json
                                                                              responce-ERROR]}}
                                  :refer-all {:level :off}}}}
  (:require [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]
            [org.httpkit.server :as httpkit]
            [clojure.string :as str]
            [bidi.ring :refer (make-handler)]
            [sm_async_api.utils.base64 :refer [b64->string]]
            [cheshire.core :as json]
            [sm_async_api.config :as config]
            [sm_async_api.utils.misc :refer [arity]]
            [taoensso.timbre.appenders.core :as appenders]
            [ring.middleware.json :refer [wrap-json-body]]
            [sm_async_api.utils.log_managment :refer [clear-log]]
            [clojure.walk :refer [stringify-keys]]
            [sm-async-api.task.sm_fake_resp :refer [responce-OK
                                                    responce-BAD-REQ
                                                    responce-INTERNAL-ERROR-GENERIC
                                                    responce-INTERNAL-ERROR
                                                    responce-NOT-ATHORIZED
                                                    responce-TOO-MANY-THREADS
                                                    responce-NO-SERVER-json
                                                    responce-NO-SERVER-no-json
                                                    responce-ERROR]]
            [ring.middleware.params :refer [wrap-params]]))


(defonce ^:private responce (atom
                             {:status 200
                              :headers {"content-type" "text/html; charset=UTF-8"}
                              :body "Just OK"}))


(defonce ^:private responce-async (atom
                                   {:status 200
                                    :headers {"content-type" "application/json; charset=UTF-8"}
                                    :body (json/generate-string {:Messages ["Sample Message"]
                                                                 :ReturnCode 0
                                                                 :Async {:Otherfield 1
                                                                         :ActionID  "ASYNCID"
                                                                         :OneMorefield 2}})}))



#_(defn- warp-debug-req [handler]
    (fn [request]
      (timbre/with-merged-config
        {:appenders {:spit (appenders/spit-appender {:fname "log/reflector.log"})}}
        (handler (spy :debug request)))))

(defn stringify-headers [resp]
  (let [headers (resp :headers)]
    (if (or (nil? headers) (string? headers))
      resp
      (assoc resp :headers (stringify-keys headers)))))

(defn- req->url [request]
  (let [{:keys [service subject action]} (request :params)]
    (str service "/" subject (when (some? action) (str "/" action)))))

(def  debug-margin    (reduce str (repeat 77 " ")))

(defn get-user [{:keys [headers]}]
  (when-let [auth  (headers "authorization")]
    (last (re-find #"^Basic (.*)$" (b64->string auth)))))




(defn- make-responce [request resp]
  (timbre/with-merged-config
    {:appenders {:spit (appenders/spit-appender {:fname "log/reflector.log"})
                 :println {:enabled? false}}}
    (try
      (let [;rsp  (#(if (and (fn? %) (= (arity %) 1)) (%) %) @resp)
             rsp  (if (fn? @resp ) (stringify-headers (@resp request)) @resp)
            ]
        (try (debug "Url:" (req->url request)
                    "User:" (get-user request)
                    "\n Header" (request :headers)
                    "\n" debug-margin  "...Body " (request :body)
                    "\n" debug-margin "...Responce" rsp)

             (catch Exception e (warnf "Debug print error %s while proccesing %s with responce %s (%s)"
                                       (ex-message e) request resp rsp)))
        rsp)
      (catch Exception e (errorf "Exepction %s while proccesing %s with responce %s"
                                 (ex-message e) request  resp)
             {:status 500
              :headers {"content-type" "text/html; charset=UTF-8"}
              :body "All dead"}))))


(defn- make-sync-responce [request]
  (make-responce request responce))

(defn- make-async-responce [request]
  (make-responce request  responce-async))

(defn- reflector-routes [base-path]
  [base-path  {[:service]    make-async-responce
               [:service "/" :subject] {:get  make-sync-responce}
               [:service "/" :subject "/" :action] make-sync-responce}])

(defn- reflector-app [routes]
  (-> routes
      (make-handler)
      ;(warp-debug-req)
      (wrap-json-body)
      (wrap-params)))

(defonce ^:private ReflectorHTTPServer (atom nil))

(defn- check-responce-value [val]
  (if (fn? val)
    (case (arity val)
      0 (stringify-headers (val))
      1 val
      (throw (AssertionError.
              (format "Responce function with arity other then 1 and 0 is not supported. %s has arity %s"
                      val (arity val)))))
    (stringify-headers val)))

(defn relector-set-responce  [val]
  (timbre/with-merged-config
    {:appenders {:spit (appenders/spit-appender {:fname "log/reflector.log"})
                 :println {:enabled? false}}}
    (reset! responce (check-responce-value val))))


#_(defn relector-set-async-responce  [val]
  (reset! responce-async (if (fn? val) (fn [] (stringify-headers (val)))
                             (stringify-headers val))))

(defn relector-set-async-responce  [val]
  (reset! responce-async  (check-responce-value val)))

(defn  reflector-start [& responce]
  (let [config (config/get-config)
        port (Integer/parseInt ((str/split (config :module_ip) #":") 1) 10)]
    (if-not (nil? @ReflectorHTTPServer)
      (fatal "Reflection sever already started.")
      (if (nil? config) (fatal "Configuration not complited yet.")
          (do
            (when (some? responce) (relector-set-responce responce))
            (reset! ReflectorHTTPServer
                    (httpkit/run-server (reflector-app (reflector-routes (config :base-path))) {:port port}))
            (info (format "Reflector server  %d. Base url %s" port (config :base-path))))))))


(defn reflector-stop []
  (when-not (nil? @ReflectorHTTPServer)
    (@ReflectorHTTPServer :timeout 100)
    (reset! ReflectorHTTPServer nil)
    (info "Reflector server stopped.")))

(defn reflector-restart [& responce]
  (reflector-stop)
  (reflector-start responce))

(defn reflector-clear-log []
  (clear-log "reflector.log"))


(comment
  (config/configure "test/config/run/")
  (reflector-start)
  (relector-set-responce responce-OK)
  (relector-set-responce responce-OK)
  (reflector-stop)
  (reflector-clear-log))

