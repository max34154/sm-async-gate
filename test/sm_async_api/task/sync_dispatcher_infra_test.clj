(ns sm_async_api.task.sync_dispatcher_infra_test
  {:clj-kondo/config  '{:linters {:unresolved-symbol
                                  {:exclude [responce-OK
                                             responce-BAD-REQ
                                             responce-INTERNAL-ERROR-GENERIC
                                             responce-INTERNAL-ERROR
                                             responce-NOT-ATHORIZED
                                             responce-TOO-MANY-THREADS
                                             responce-NO-SERVER-json
                                             responce-NO-SERVER-no-json
                                             responce-ERROR
                                             with-fake-http]}
                                  :refer-all {:level :off}
                                  :lint-as {deftest clojure.core/def}}}}
  (:require
   [sm_async_api.task.sync_dispatcher :as sd]
   [sm_async_api.utils.http_fake :refer :all]
   [clojure.test :refer [testing  deftest  is use-fixtures]]
   ;[clojure.walk :refer [keywordize-keys]]
   [taoensso.timbre :as timbre]
   [sm_async_api.config :as config]
   ;[sm_async_api.task.writers :as tw]
   [sm-async-api.task.sm_fake_resp :refer :all]
   ;[sm_async_api.http_errors :as http-errors]
   ))

#_(defn keywordize-header [{:keys [headers] :as resp}]
    (assoc resp :headers (keywordize-keys headers)))

#_(def test_db
    {:classname   "org.h2.Driver"
     :subprotocol "h2:mem"
     :subname     "demo;DB_CLOSE_DELAY=-1"
     :user        "sa"
     :password    ""})


#_(def test_data (slurp "test/sm_async_api/test_despetcher.sql"))

#_(timbre/merge-config! {:appenders  {:println {:enabled? true}}})

#_(defn fix-test-db [t]
    (open_db test_db)
    (check_db)
    (t))


#_(defn result-emulator
    ([size name]
     (into [] (for [i (range size)] {:req_id i :action "test action" :user_name name})))
    ([size]
     (into [] (for [i (range size)] {:req_id i :action "test action" :user_name "noname"}))))

#_(defn named-result-emulator
    [id size condition]
    (into [] (for [i (range size)] {:req_id i :action "test action"
                                    :user_name condition
                                    :locked_by id
                                    :execution_mode 'I'})))

#_(defn async-result-emulator
    [id size _]
    (into [] (for [i (range size)] {:req_id i :action "test action"
                                    :user_name "any"
                                    :locked_by id
                                    :execution_mode 'S'})))


(defn thread-deference [[name threads]]
  ;(println name " =-> "  threads "= " (:threads (sd/pusher-manager-get-pusher  name)))
  (- threads (:threads (sd/pusher-manager-get-pusher  name))))

(defn tread-inventory []
  (for [pusher  @sd/online-pushers]
    [(pusher 0)  ((pusher 1) :threads)]))


#_(defn default-result-emulator
    [id size condition]
    (into [] (for [i (range size)] {:req_id i :action "test action"
                                    :user_name (str "Not in the list:" condition)
                                    :locked_by id
                                    :execution_mode 'S'})))

;(defn request [url resp pusher]
;  (with-fake-http [url (keywordize-header resp)]
;    (pusher test-action "test-thread")))

(defn fix-read-config [t]
  (timbre/set-level! :debug)
  (config/configure "test/")
  (t))

(deftest build-dispatcher-infra-test
  (testing "Infrastructre managment"
    (sd/pusher-manager-run)
    (let [pushers @sd/online-pushers]
      (testing  "Tesing pusher creator "
        (is (some? pushers)))
      (testing  "Testing thread killer"
        (let [pushers (tread-inventory)]
          (Thread/sleep 2000)
          (sd/pusher-manager-do-all sd/pusher-manager-kill-thread)
          (Thread/sleep 1000)
          (doseq [pusher pushers]
            (testing (str "Tread remover for pusher " (pusher 0))
              (is (= 1  (thread-deference pusher))))))))))

(deftest close-channel-test
  (testing "Close channel test"
    (sd/pusher-manager-run)
    (let [pushers         @sd/online-pushers ]
      (Thread/sleep 2000)
      (sd/shatdown-pushers 2)
      ;(sd/pusher-manager-do-all sd/pusher-manager-kill)
      ;(Thread/sleep 1000)
      (doseq [pusher pushers]
        (testing (str "Check if all threads a removed" (pusher 0))
          (is (= 0 ((@sd/online-pushers (pusher 0)) :threads))))))))

(use-fixtures :once fix-read-config)

