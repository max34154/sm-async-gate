(ns sm_async_api.utils.dispatcher
  (:require  [clojure.core.async
              :refer [>!!  close!]]
             [taoensso.timbre :as timbre
              :refer [info report reportf infof]]))

(def ^:const fetch-marker "FETCH")

(def ^:const command-exit "EXIT")

;pusher-manager-kick
(defn kick
  "Run task/message reading cycle"
  [[_ {:keys [reader-control]}]]
  (>!! reader-control  fetch-marker))


;pusher-manager-kill
(defn kill
  "Stop reading cycle and exit reader, 
   close task/message channel, 
   pushers/messengers complete all tasks in channel then exit"
  [[_ {:keys [reader-control task-buffer]}]]
  (close! reader-control)
  (close! task-buffer))

;pusher-manager-kill-reader
(defn kill-reader
  "Stop reading cycle and exit reader"
  [[_ {:keys [reader-control]}]]
  (close! reader-control))

;pusher-manager-kill-pusher
(defn kill-pusher
  "Close task/message channel, 
   pushers/messengers complete all tasks in channel then exit"
  [[_ {:keys [task-buffer]}]]
  (close! task-buffer))

;kill-thread
(defn kill-thread
  "Close one pusher/messenger thread by 
    placing exit command into channel"
  [[_ {:keys [task-buffer]}]]
  (>!!   task-buffer command-exit))

;pusher-manager-kill-all-threads
(defn kill-all-threads
  "Close all pusher/messenger threads placing exit commands.
   Depriciated, user kill-pusher instead"
  [[_ {:keys [task-buffer threads]}]]
  (dotimes [_ threads] (>!!   task-buffer command-exit)))

(defn print-status [[name {:keys [mode threads condition]}]]
  (println "Name: "  name " Mode " mode " Threads " threads)
  (println "      Condition:" condition))


(defn do-all [dispatcher action]
  (doseq [pusher @dispatcher] (action pusher)))


(defn count-readers [total [_ {:keys [reader-exited]}]]
  (if (true? reader-exited) total (inc total)))

(defn count-threads [total [_ {:keys [threads]}]]
  (if (nil? threads) total (+ total threads)))


(defn stop
  ([dispatcher] (stop dispatcher 10))
  ([dispatcher max-wait-time]
   (info "Shutdown sequece started.")
   ;(when (timbre/may-log? :debug) (do-all dispatcher print-status))
   (do-all dispatcher kill-reader)
   (loop [t  max-wait-time]
     (let [active-readers (reduce count-readers 0 @dispatcher)]
       (if (zero? active-readers)
         (info "No more readers active")
         (if  (> t 0)
           (do
             (infof "%s readers sill running..." t)
             (Thread/sleep 1000)
             (recur (dec t)))
           (report "Readers grace period expired. %s readers still running." active-readers)))))

   (do-all  dispatcher kill-pusher)
   (loop [t max-wait-time]
     (let [active-threads (reduce count-threads 0 @dispatcher)]
       (if (zero? active-threads)
         (info "No more pusher/messenger threads active.")
         (if  (> t 0)
           (do
             (infof "%s  threads sill running..." t)
             (Thread/sleep 1000)
             (recur (dec t)))
           (reportf "Pusher/messenger grace period expired. %s pusher/messenger threads still running." active-threads)))))))
