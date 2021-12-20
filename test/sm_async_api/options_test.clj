  
(ns ^:util sm-async-api.options-test
  {:clj-kondo/config  '{:linters {:unresolved-symbol
                                  {:exclude [testing use-fixtures deftest is are]}
                                  :refer-all {:level :off}}}}
  (:require [sm_async_api.utils.options :as opts]
            [clojure.test :refer [testing
                                  deftest is]]))

(deftest no-parameters-option
  (testing "No parameters"
    (testing "- normal"
      (let [config (opts/read-options (list "-clean"))]
        (is (some? config))
        (is (some? (:-clean config)))
        (is (= [] (:-clean config)))))
    (testing "- skip leading params"
      (let [config (opts/read-options (list "param1" "param2" "-clean"))]
        (is (some? config))
        (is (some? (:-clean config)))
        (is (= [] (:-clean config)))))
    (testing " - extra prameters"
      (let [config (opts/read-options (list "-clean" "extra-param"))]
        (is (nil? config))))))

(deftest one-parameters-option
  (testing "One parmeter option"
    (testing "- normal"
      (let [config (opts/read-options (list "-port" 3000))]
        (is (some? config))
        (is (some? (:-port config)))
        (is (= [3000] (:-port config)))))
    (testing " - less prameters"
      (let [config (opts/read-options (list "-port"))]
        (is (nil? config))))
    (testing " - extra prameters"
      (let [config (opts/read-options (list "-port" 3000 "extra-param"))]
        (is (nil? config))))))


(deftest one-two-parameters-option
  (testing "2 or 3 parameters option"
    (testing "- normal - 2 parameters"
      (let [config (opts/read-options (list "-testoption" 3000 4000))]
        (is (some? config))
        (is (some? (:-testoption config)))
        (is (= [3000 4000] (:-testoption config)))))
    (testing "- normal - 3 parameters"
      (let [config (opts/read-options (list "-testoption" 3000 4000 6000))]
        (is (some? config))
        (is (some? (:-testoption config)))
        (is (= [3000 4000 6000] (:-testoption config))))))
  (testing " - less prameters"
    (let [config (opts/read-options (list "-testoption" 3000))]
      (is (nil? config))))
  (testing " - extra prameters"
    (let [config (opts/read-options (list "-testoption" 3000 4000 6000 9000))]
      (is (nil? config)))))


(deftest parameter-list
  (testing "Several options"
    (let [config (opts/read-options (list "-clean" "-port" 3000 "-path" "./conf" "-testoption" 3000 4000))]
      (is (some? config))
      (is (= [] (:-clean config)))
      (is (= [3000] (:-port config)))
      (is (= ["./conf"] (:-path config)))
      (is (= [3000 4000] (:-testoption config))))))

(deftest read-defauts
  (testing "read options"
    (let [config (opts/read-options (list "-clean"  "-path" "./conf" "-testoption" 3000 4000)
                                    {:-port [6000] :-testoption [2 4]})]
      (is (some? config))
      (is (= [] (:-clean config)))
      (is (= [6000] (:-port config)))
      (is (= ["./conf"] (:-path config)))
      (is (= [3000 4000] (:-testoption config))))))

