
(ns sm_async_api.enum.process_result)

;; Codes for result of processing sm responce ( http and RC code )
;;
;;


(def ^:const ^Integer OK "0 - Request processed successfully"  0)

(def  ^:const ^Integer   TOO-MANY-THREADS
 "1 - Request NOT processed, this user has to many opened threads" 1)

(def  ^:const ^Integer  SERVER-NOT-AVAILABLE 
   "2 - Request NOT processed, SM server not available" 2)

(def  ^:const ^Integer  NOT-ATHORIZED 
  "3 - Request NOT processed due to athorization error" 3)

(def  ^:const ^Integer ERROR
 "4 - Request NOT processed, internal http client error" 4)