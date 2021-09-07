#_{:clj-kondo/ignore [:unused-referred-var]}
(ns sm_async_api.task.process_result
  (:require
   [sm_async_api.enum.sm :as sm]
   [clojure.string :as s]
   [cheshire.core :as json]
   [sm_async_api.config :as config]
   [sm_async_api.utils.macro :refer [ _case ]]
   [sm_async_api.http_errors :as http-errors]
   [sm_async_api.task.writers :as tw]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]))

(def ^:const OK  0)

(def  ^:const TOO-MANY-TREADS 1)

(def  ^:const SERVER-NOT-AVAILABLE 2)

(def  ^:const NOT-ATHORIZED 3)

(def  ^:const ERROR 4)

#_(def ^:private server-not-availalbe-sleep (get cconfig/config :server-not-availalbe-sleep))

#_(def ^:private to-many-threads-sleep (get cconfig/config :to-many-threads-sleep))



(defmacro tread-details
  ([opts] `(format "Thread:%s,Mode:%s,User:%s,RecID:%s,Url:%s"
                  (~opts :thread) (~opts :mode)
                  (~opts :user-name) (~opts :rec-id) (~opts :url)))

  ;([opts]  `(let [{:keys [thread mode user-name rec-id url]} ~opts]
  ;            (format "Thread:%s,Mode:%s,User:%s,RecID:%s,Url:%s"
  ;                    (~opts :thread) (~opts :mode)
  ;                    (~opts :user-name) (~opts :rec-id) (~opts :url))))

  ([prev opts  post]
   `(str (if (nil? ~prev) "" ~prev)
         (tread-details ~opts)
         (if (nil? ~post) "" ~post))))

(defn process
  "Process responce from SM. If ok - write result to db.
   Return code must by processed by push managers"
  [{:keys [status opts body headers error]} ]
  (if (or error (nil? status) (nil? opts))
    (do
      (fatal "Failed, exception: error " error " status " status  )
      (fatal (tread-details "!!!!" opts " - exited!!!"))
      ERROR)
    (_case status
       http-errors/OK    (do ; - just in case of http success and error in error code 
                          (if (and (s/includes? (headers :content-type) "application/json")
                                   (= (get (json/parse-string body) "ReturnCode") sm/RC_SUCCESS))
                            (@tw/result-writer (opts :rec-id) body status)
                            (@tw/action-rescheduler  (opts :rec-id) body status))
                          OK)

      http-errors/Unathorized (let [jbody (json/parse-string body)]
                                (if  (= (get jbody "ReturnCode") sm/RC_WRONG_CREDENTIALS)
                                  (if (and (some? (jbody "Messages"))
                                            (s/includes? ((jbody "Messages") 0) "Not Authorized"))
                                    (do
                                      (timbre/error "Not Authorized. Write the answer. Body:"  body)
                                      (@tw/result-writer (opts :rec-id) body status)
                                      NOT-ATHORIZED)

                                    (do
                                      (warn (tread-details  "Two many threads." opts
                                                            (format "Thread will sleep for %dмс."  (config/get-config :to-many-threads-sleep))))
                                      (Thread/sleep (config/get-config :to-many-threads-sleep))
                                      TOO-MANY-TREADS))
                                  (do
                                    (@tw/result-writer (opts :rec-id) body status)
                                    (timbre/error "Unkown athorization error:"  body)
                                    OK)))

      http-errors/Not-Found  (if  (and  (s/includes? (headers :content-type) "application/json")
                                        (= (get (json/parse-string body) "ReturnCode") sm/RC_NO_MORE))
                               (do
                                 (@tw/result-writer (opts :rec-id) body status)
                                 (timbre/error "Attempt to use incorrect service name:" body)
                                 OK)

                               (do
                                 (timbre/error (tread-details "SM not available - " opts "."))
                                 (Thread/sleep (config/get-config :server-not-availalbe-sleep))
                                 SERVER-NOT-AVAILABLE))

      http-errors/Bad-Request  (do
                                 (@tw/result-writer (opts :rec-id) body status)
                                 OK)

       http-errors/Internal-Server-Error (do
                                          (if (and (s/includes? (headers :content-type) "application/json")
                                                   (= (get (json/parse-string body) "ReturnCode") sm/RC_WRONG_CREDENTIALS))
                                            (@tw/action-rescheduler  (opts :rec-id) body status)
                                            (@tw/result-writer (opts :rec-id) body status))
                                          OK)

      (do
        (error (tread-details "UnSuccess. Server error in " opts "."))
        (@tw/result-writer (opts :rec-id) body status)
        OK))))