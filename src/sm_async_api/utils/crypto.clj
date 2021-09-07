(ns sm_async_api.utils.crypto
  (:require [buddy.core.codecs :as codecs]
         [buddy.core.nonce :as nonce]
         [buddy.core.crypto :as crypto]
         [buddy.core.kdf :as kdf]
         [sm_async_api.utils.base64 :as b64 
          :refer [ b64->bytes string->b64 bytes->b64] ])
  
;(:import (java.util Base64))
  )

;(defn bytes->b64 [^bytes b] (String. (.encode (Base64/getEncoder) b)))
;(defn b64->bytes [^String s] (.decode (Base64/getDecoder) (.getBytes s)))


(defn slow-key-stretch-with-pbkdf2 [weak-text-key n-bytes]
  (kdf/get-bytes
   (kdf/engine
    {:key weak-text-key
      ;; Keep this constant across runs
     :salt (b64->bytes (string->b64 "YW55WHRzZDAx"))
     :alg :pbkdf2
     :digest :sha512
     ;; Target O(100ms) on commodity hardware
     :iterations 1e5})
   n-bytes))

(defn encrypt
  "Encrypt and return a {:data <b64>, :iv <b64>} that can be 
  decrypted with the same `password`."
  [clear-text password]
  (let [initialization-vector (nonce/random-bytes 16)]
    {:data (bytes->b64
            (crypto/encrypt
             (codecs/to-bytes clear-text)
             (slow-key-stretch-with-pbkdf2 password 64)
             initialization-vector
             {:algorithm :aes256-cbc-hmac-sha512}))
     :iv (bytes->b64 initialization-vector)}))

(defn decrypt
  "Decrypt and return the clear text for some output of `encrypt` 
  given the same `password` used during encryption."
  [{:keys [data iv]} password]
  (codecs/bytes->str
   (crypto/decrypt
    (b64->bytes data)
    (slow-key-stretch-with-pbkdf2 password 64)
    (b64->bytes iv)
    {:algorithm :aes256-cbc-hmac-sha512})))

