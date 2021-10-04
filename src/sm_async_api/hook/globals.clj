(ns sm_async_api.hook.globals )
  
(def ^:const fetch-marker "FETCH")

(def ^:const command-exit "EXIT")

(defonce online-messangers (atom {}))

(def ^:const default-thread-count 1)

(def ^:const default-chank-size 10)

(def ^:const default-new-task-waiting 2000)