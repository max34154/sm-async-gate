(ns sm_async_api.config
  {:clj-kondo/config  '{:linters {:unused-referred-var
                                  {:exclude {taoensso.timbre [log  trace  debug  info  warn  error  fatal  report
                                                              logf tracef debugf infof warnf errorf fatalf reportf
                                                              spy get-env]}}}}}
  (:refer-clojure :exclude [load])
  (:require [yaml.core :as yaml]
            [sm_async_api.utils.crypto :refer [encrypt decrypt]]
            [sm_async_api.utils.base64 :refer [string->b64]]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]
            [clojure.java.io :as io]))

;; Parse a YAML file
(def ^:const default-values
  '([:to-many-threads-sleep 100]
    [:server-not-availalbe-sleep 3000]
    [:auth-service "asyncuser"]
    [:async-service "async"]
    [:base-path "/SM/9/rest/"]))

(def default-lock-chunk-size 10)

(def default-threads-per-manager 2)


;(defonce module_name (atom "undefined"))

;(defn set-module-name [name] (reset! module_name name))

;(defmacro get-module-name []  '@sm_async_api.config/module_name)

(def keystore (atom {}))

(def config (atom {}))

(def default-conf-path "")

(defn- get-key [key]
  (@keystore key))

(defn- decode-password
  ([raw login password key]
   (let [key (get-key key)]
     (if (nil? key)  raw
         (assoc raw password (decrypt key (raw login)))))))


(defn- encode-password
  ([raw login password key]
   (swap! keystore assoc  key (encrypt (password raw) (login raw)))
   (dissoc raw password)))


(defn- build-mime-types [raw]
  (assoc raw :mime-types (reduce #(assoc %1 (keyword %2) true)
                                 {}
                                 (raw :mime-types))))

(def ^:private ^:const keylist [[:global-login :global-password  :g]
                                [:async-login :async-password :a]
                                [:login :password :c]])

(defn- decoder [raw]
  (reduce   (fn  [raw  creads]
              (let [login (creads 0)
                    password (creads 1)
                    key (creads 2)]
                (if  (nil? (login raw))
                  raw
                  (if (nil? (password raw))
                    (decode-password raw login password key)
                    (do
                      (encode-password raw login password key)
                      raw)))))
            raw keylist))

(defn- password-remover [raw]
  (reduce #(dissoc %1 (%2 1)) raw keylist))


(defn set-default [config default]
  (if (nil? (default 0))
    (do (error "Incorrect default " default ". Skiped.")
        config)
    (if (nil? (config (default 0)))
      (assoc config  (default 0) (default 1))
      config)))

(defn fill-config-defaults [config]
  (assoc config :config
         (reduce set-default
                 (config :config)
                 default-values)))

(defn calc-config-defaults [config]
  (assoc config :config (->> (config :config)
                             (#(assoc % :base-url
                                      (str "http://" (%  :module_ip) (% :base-path))))
                             (#(assoc % :auth-url
                                      (str  (% :base-url) (% :auth-service))))
                             (#(assoc % :async-action-url
                                      (str  (% :base-url) (% :async-service))))
                             build-mime-types)))

(defn read-config [path]
  (let [base-file (str path "sm_async.yml")
        workers-file (str path "workers.yml")
        keystore-file (str path "keystore.yml")]
    (with-local-vars [local-config* {}]
      (let [workers  (yaml/from-file workers-file)
            ;base (base-config-builder base-file)
            base (yaml/from-file base-file)]
        (when (and (some? workers) (some? base))
          (reset! keystore (yaml/from-file keystore-file))
          (var-set local-config* (-> {} (assoc :workers (decoder workers))
                                     (assoc :config (decoder base))))
          (with-open [w (io/writer  keystore-file)]
            (.write w (yaml/generate-string  @keystore)))
          (with-open [w (io/writer  workers-file)]
            (.write w (yaml/generate-string (password-remover workers))))
          (with-open [w (io/writer  base-file)]
            (.write w (yaml/generate-string (password-remover base))))
          (var-get local-config*))))))

(defn- fill-workers [workers]
  (assoc workers :dedicated
         (for [w (workers :dedicated)]
           (->> w
                (#(if (nil? (:get-allowed %))
                    (assoc % :get-allowed (workers :dedicated-get-allowed)) %))
                (#(if (nil? (:threads %))
                    (assoc % :threads (workers :dedicated-threads)) %))
                (#(if (nil? (:chank-size %))
                    (assoc % :chank-size (workers :dedicated-chank-size)) %))))))

(defn- configure-workers [global-configuration]
  (assoc global-configuration :workers
         (->> global-configuration
              :workers
              (#(if (= (% :dedicated-get-allowed) true) %
                    (assoc % :dedicated-get-allowed false)))
              (#(if (nil? (% :dedicated-threads))
                  (assoc % :dedicated-threads default-threads-per-manager)
                  %))
              (#(if (nil? (% :dedicated-chank-size))
                  (assoc % :dedicated-chank-size default-lock-chunk-size)
                  %))
              (#(assoc % :global-credentials
                       (string->b64 (str "Basic " (% :global-user) ":" (% :global-password)))))
              (#(assoc % :async-credentials
                       (string->b64 (str "Basic " (% :async-user) ":" (% :async-password)))))
              fill-workers)))

(defn create-uid-generator [_config]
  (assoc  _config  :get-unique-id
          (let [i (atom 0)
              module-name (-> _config  :config :async_gateway_id)]
            (fn  []
              (format "%X-%X-%s" 
                      (quot (System/currentTimeMillis) 1000) 
                      (swap! i inc)
                      module-name)))))


(defn configure [path]
  (reset! config
          (when-let [_config (read-config
                              (if (empty? path) default-conf-path path))]
            (-> _config
                fill-config-defaults
                calc-config-defaults
                configure-workers
                create-uid-generator
                ))))




#_(defn old_configure [path]
  (reset! config
          (configure-workers
           (calc-config-defaults
            (fill-config-defaults
             (read-config
              (if (empty? path) default-conf-path path)))))))

(defmacro get-config
  ([]  `(@sm_async_api.config/config :config))
  ([key] `(-> @sm_async_api.config/config :config ~key)))

(defmacro get-workers
  ([]  `(@sm_async_api.config/config :workers))
  ([key] `(-> @sm_async_api.config/config :workers ~key)))

(defmacro get-module-name [] `(-> @sm_async_api.config/config :config :async_gateway_id))

(defmacro get-uid []  `(( @sm_async_api.config/config  :get-unique-id)))


;(defn set-module-name [name] (reset! module_name name))



