(ns sm_async_api.utils.macro
  {:clj-kondo/config  '{:linters {:unused-public-var {:level :off}}
                        :clojure-lsp/unused-public-var {:level :off}}})


;{:exclude {taoensso.timbre [log  trace  debug  info  warn  error  fatal  report
;                            logf tracef debugf infof warnf errorf fatalf reportf
;                            spy get-env]}}
;
#_{:clj-kondo/ignore [:unused-public-var]}
(defmacro _qoute [_str] `(str "'" ~_str "'"))



(defn- case_list  [ cases]
   (let [lng (count cases)]
               (map (fn [a b ]  
                      (if (even? b)  a 
                       (if (= b lng) a (eval a))))
                    cases
                    (iterate inc 1))))

(defmacro _case [v & cases]
  (conj (case_list   cases) v 'case ))

(defmacro tod []
   `(System/currentTimeMillis) )

(defmacro tod-seconds []
  `(quot (System/currentTimeMillis) 1000))

(defmacro unixtime->timestamp [t]
  `(.toString (new java.sql.Timestamp ~t)))