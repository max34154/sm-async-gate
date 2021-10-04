(ns sm_async_api.http_errors
  (:require [clojure.string :as str]))


(def ^:const ^Integer OK 200)

(def ^:const ^Integer Movied-Premanently 
  "301 - The URL of the requested resource has been changed permanently. 
   The new URL is given in the response"
  301)

(def ^:const ^Integer Bad-Request 
  "400 - The server could not understand the request due to invalid syntax." 
  400)

(def ^:const ^Integer Unathorized 
  "401 - Although the HTTP standard specifies 'unauthorized', semantically this response means 'unauthenticated'. 
   That is, the client must authenticate itself to get the requested response."
  401)

(def ^:const ^Integer  Not-Found  
  "404 - In an API, this can also mean that the endpoint is valid but the resource itself does not exist. 
   Servers may also send this response instead of 403 to hide the existence of a resource from an unauthorized client. "
  404
  )

(def ^:const ^Integer Forbiden 
  "403 - The client does not have access rights to the content; that is, it is unauthorized, 
   so the server is refusing to give the requested resource. 
   Unlike 401, the client's identity is known to the server" 
  403)


(def ^:const ^Integer  Method-Not-Allowed 
  "405 - The request method is known by the server but is not supported by the target resource. 
   For example, an API may forbid DELETE-ing a resource"
  405)

(def ^:const ^Integer Not-Acceptable 
  "406 - This response is sent when the web server, after performing server-driven content negotiation, 
   doesn't find any content that conforms to the criteria given by the user agen" 
  406 )

(def ^:const ^Integer Internal-Server-Error 
  "500 The server has encountered a situation it doesn't know how to handle." 
  500 )

(def ^:const unathorized-401
  {:status Unathorized
   :headers {"content-type" "application/json"}
   :body  "{\"Messages\":[\"Unathorized\"], \"ReturnCode\":28}"})

(def ^:const forbiden-403
  {:status Forbiden
   :headers {"content-type" "application/json"}
   :body  "{\"Messages\":[\"Access forbiden\"], \"ReturnCode\":28}"})

(def ^:const no-results-200
  {:status OK
   :headers {"content-type" "application/json"}
   :body  "{\"Messages\":[\"No records found\"], \"ReturnCode\":9}"})


(def ^:const ok-200
  {:status  OK
   :headers {"content-type" "application/json"}
   :body "{\"ReturnCode\":0}"})


(defmacro ^:const single-result-200 [resp]
  `{:status  OK
    :headers {"content-type" "application/json"}
    :body (json/generate-string (assoc ~resp :ReturnCode 0))})


(defmacro internal-50x 
  ([] `{:status Internal-Server-Error
        :headers {"content-type" "application/json"}
        :body (str "{\"ReturnCode\":-1,"
                   "\"Messages\":[]}")})
  ([result] 
     `{:status (or (:status ~result) Internal-Server-Error)
       :headers {"content-type" "application/json"}
       :body (str "{\"ReturnCode\":-1,"
                  "\"Messages\":[\"Error code:" ~result "\"]}")})
  ([req result]
  `{:status (or (:status ~result) Internal-Server-Error)
    :headers {"content-type" "application/json"}
    :body (str "{ \"rec_id\":\"" (-> ~req :route-params :action_id)  "\","
               "\"ReturnCode\":-1,"
               "\"Messages\":[\"Error code:" ~result "\"]}")}))


(defmacro not-found-404
  ([]
   `{:status Not-Found
     :headers {"content-type" "application/json"}
     :body  "{\"Messages\":[], \"ReturnCode\":-1}"})
  ([message]
   `{:status Not-Found
     :headers {"content-type" "application/json"}
     :body ~(if (str/starts-with? message "(")
              `(str "{\"Messages\":[\"" ^String ~message "\"], \"ReturnCode\":-1}")
              (str "{\"Messages\":[\"" message "\"], \"ReturnCode\":-1}"))}))

(defmacro validation-error-406
  ([] '{:status Not-Acceptable
        :headers {"content-type" "application/json"}
        :body "{\"Messages\":[], \"ReturnCode\":71}"})
  ([message] `{:status Not-Acceptable
               :headers {"content-type" "application/json"}
               :body ~(if (str/starts-with? message "(")
                        `(str "{\"Messages\":[\"" ^String ~message "\"], \"ReturnCode\":71}")
                        (str "{\"Messages\":[\"" message "\"], \"ReturnCode\":71}"))})
  ([message error] `{:status Not-Acceptable
                     :headers {"content-type" "application/json"}
                     :body ~(if (or (str/starts-with? message "(") (str/starts-with? error "("))
                              `(str "{\"Messages\":[\"" ^String ~message "\"], \"ReturnCode\":" ^int ~error "}")
                              (str "{\"Messages\":[\"" message "\"], \"ReturnCode\":" error "}"))}))


(defn friendly-assertion-errors [e]
  (cond
    (str/includes? e "empty ct") "Content-not specified"
    (str/includes? e "mime-type")  "Unsupported mime-type"
    (str/includes? e "attachment-size")  "File too large"
    :else (str "Incorrect attachment paramaters:" e)))
