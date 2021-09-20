(ns sm_async_api.enum.sm 
   {:clj-kondo/config  '{:linters {:unused-public-var 
                                   {:level :off}}}})
;
; !! Attention - SM special message for busy or locked object can be 
;
; Depends on something it can be 
;  http error 400 and ReturnCode 3 
;  http error 500 and ReturnCode -1 - if script exited with $L.exit = "error"

(def  ^:const  RC_UNABLE_TO_DELETE_FILE  
  "57 - The operation failed. Check to make sure the file name is correct, that the file exists, and that it is not read-only."  
  57)
(def  ^:const   RC_UNABLE_TO_CLOSE_FILE  
  "47 - The operation failed. Check to make sure the file name is correct, that the file exists, and that it is not read-only."  
  47)
(def  ^:const   RC_UNABLE_TO_WRITE_TO_FILE  
  "20 - The operation failed. Check to make sure the file name is correct, that the file exists, and that it is not read-only."  
  20)
(def  ^:const   RC_VALIDATION_FAILED  
  "71 - The operation failed because the data supplied in a field or the record did not pass validity checks performed by the application."  
  71)
(def  ^:const   RC_NOT_AUTHORIZED  
  "28 - The request operation was not performed due to an authorization failure. Check the permissions associated with the user who submitted the request."  
  28)
(def  ^:const   RC_BAD_QUERY  
  "66 - The query failed due to incorrect query syntax."  
  66)
(def  ^:const   RC_DELETED  
  "52 - The operation failed because the record was deleted by another user or process."  
  52)
(def  ^:const   RC_MODIFIED  
  "51 - The update operation failed because the record was modified by another user or process since you read it."  
  51)
(def  ^:const   RC_DUPLICATE_KEY  
  "48 - The insert operation failed because the file already contains a record with this unique key value."  
  48)
(def  ^:const   RC_NO_MORE  
  "9 - No more records are available in the result set." 
  9)
(def  ^:const   RC_CANT_HAVE  
  "3 - The operation failed because the resource is unavailable because some other user or process has the resource locked."  
  3)
(def  ^:const   RC_SUCCESS  
  "0 - The operation succeeded."  
  0)
(def  ^:const   RC_ERROR  
  "-1 - Some other error occurred. Examine the contents of the Messages view or the sm.log file for more information."  
  -1)


(def  ^:const   RC_WRONG_CREDENTIALS
  "-4 - Don't have description in SM. Possible generates for wrong credentials"
  -4)