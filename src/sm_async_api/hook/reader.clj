(ns sm_async_api.hook.reader
  {:clj-kondo/config  '{:linters {:unused-referred-var
                                  {:exclude {taoensso.timbre [log  trace  debug  info  warn  error  fatal  report
                                                              logf tracef debugf infof warnf errorf fatalf reportf
                                                              spy get-env]}}}}}
  (:require
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]
   [taoensso.timbre.appenders.core :as appenders]
   [clojure.core.async
    :as a
    :refer [>!! <!!
                     ; >! <!  go  chan  close! go-loop alts! alts!! timeout buffer sliding-buffer thread
            ]]
   [sm_async_api.config :as config]
   [sm_async_api.hook.globals :as hg :refer [fetch-marker command-exit online-messangers]]))

(defn- exit-reader [^String id]
  (if (some? (@online-messangers id))
    (do
      (swap! online-messangers update-in [id :reader-exited] (fn [_] true))
      (reportf  "Reader %s exited." id))
    (error (format "Reader %s not found in readers list: %s" id @online-messangers))))

(defn message-reader [in out
                      chank-size
                      prefetch-marker-position
                      ^String id
                      ^String condition
                      reader]
  (timbre/with-merged-config
                {:println {:enabled? false}
                 :appenders {:spit (appenders/spit-appender {:fname (str "log/" id ".log")})}}
  (debug id ":Waiting for command.")
  (let [new-task-waiting  (or (config/get-executors-globals :new-task-waiting)  hg/default-new-task-waiting)]
    (a/thread
      (loop [input (<!! in)]
        (when-not (or (nil? input) (= command-exit input))
          (let [result-set (reader id chank-size condition)]
            (Thread/sleep 5); avoid double reading 
            #_(timbre/with-merged-config
                {:println {:enabled? false}
                 :appenders {:spit (appenders/spit-appender {:fname (str "log/" id ".log")})}}
                (if (empty? result-set)
                  (debug id ":zero fetch")
                  (debug id ":fetched items "
                         (reduce #(str %1 ":"  (:req_id %2))  "" result-set) ":")))
            (if (empty?  result-set)
              (do (debug id ": no actions available, fall sleep for " new-task-waiting "ms")
                  (>!! in  fetch-marker)
                  (Thread/sleep new-task-waiting))
              (if (zero? prefetch-marker-position)
                (do
                  (doseq [result result-set] (>!! out result))
                  (>!! out  fetch-marker))
                (loop [result-set result-set
                       pos 0]
                  (let [f (first result-set)]
                    (if (nil? f)
                      (when-not (> pos prefetch-marker-position) (>!! out  fetch-marker))
                      (do
                        (debug id ": queue message " f)
                        (>!! out  f)
                        (when (= pos prefetch-marker-position) (>!! out  fetch-marker))
                        (recur (rest result-set) (inc pos)))))))))
          (recur (<!! in))))
      (exit-reader id)))))