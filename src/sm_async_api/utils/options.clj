(ns sm_async_api.utils.options
  (:require [yaml.core :as yaml]
            [taoensso.timbre :as timbre]))


(def options-file "resources/options.yml")

(def usage-file "resources/usage.yml")


(defn- options-normalizer [[k v]]
  {(keyword (str "-" (name k)))
   (if (nil? (:val v))
     (assoc v :val [])
     v)})

(def options-config (reduce conj {} (map options-normalizer (:options (yaml/from-file options-file)))))

(def usage (:usage (yaml/from-file usage-file)))

(defmacro option? [option] `(= (get ~option 0) \-))


(defn- get-option-config [option]
  (let [opt-config (options-config  option)]
    (if (some? opt-config)
      opt-config
      (throw (AssertionError. (str "Unknown option " option))))))




(defn- add-option [config ^String param]
  (let [option (keyword param)
        option-config (get-option-config option)]
    (assoc config option [];(option-config :val)
           :last-option (assoc option-config
                               :params-count 0
                               :name option))))

(defn- more-params-requered? [{:keys [params-count params-min]}]
  (and (some? params-count)
       (some? params-min)
       (< params-count  params-min)))

(defn- max-parameters-exceeded? [{:keys [params-count params-max]}]
  (and (some? params-count)
       (some? params-max)
       (>= params-count  params-max)))

(defn- add-param [config param]
  (-> config
      (update-in  [(-> config :last-option :name)] conj  param)
      (update-in  [:last-option :params-count] inc)))

(defn- compile-options [{:keys [last-option] :as config} param]
  (if (option? param)
    (if (more-params-requered? last-option)
      (throw (AssertionError. (format "Option %s requers at least %s parameters, only %s specified."
                                      (:name last-option)
                                      (:params-min last-option)
                                      (:params-count last-option))))
      (add-option config param))
    (if (some? last-option)
      (if (max-parameters-exceeded? last-option)
        (throw (AssertionError. (format "Option %s parameter's limit is %s. Limit exceeded."
                                        (:name last-option)
                                        (:params-max last-option))))
        (add-param config param))
      (timbre/warnf "Unexpected parameter %s. Skipped." (str param)))))

(defn- last-option-check [config]
  (if (some? (:last-option config))
    (if (#(< (:params-count %) (:params-min %)) (:last-option config))
      (let [last-option (:last-option config)]
        (throw (AssertionError. (format "Option %s requers at least %s parameters, only %s specified."
                                        (:name last-option)
                                        (:params-min last-option)
                                        (:params-count last-option)))))
      (dissoc config :last-option))
    config))

(defn read-options
  ([options] (read-options options {}))
  ([options  default-config]
   (try
     (if (seq options)
       (last-option-check (reduce compile-options  default-config  options))
       default-config)
     (catch AssertionError e (timbre/fatalf "Options processing error %s" (ex-message e))
            (print usage)))))

(defn get-option-defaults [option]
  (-> options-config  option :val))