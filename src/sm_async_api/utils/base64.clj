(ns sm_async_api.utils.base64
   (:require  [clojure.string :as str])
  (:import java.util.Base64))

(defn byte-transform
  "Used to encode and decode strings.  Returns nil when an exception
        was raised."
  [direction-fn string]
  (try
    (str/join (map char (direction-fn (.getBytes string))))
    (catch Exception _)))

(defn b64->string 
  "Will do a base64 decoding of a string and return a string."
  [^String string]
  (byte-transform #(.decode (Base64/getDecoder) %) string))

(defn b64->bytes [^String s] (.decode (Base64/getDecoder) (.getBytes s)))

(defn string->b64
  "Will do a base64 decoding of a string and return a string."
  [^String string]
  (byte-transform #(.encode (Base64/getEncoder) %) string))

(defn bytes->b64 [^bytes b] (String. (.encode (Base64/getEncoder) b)))

