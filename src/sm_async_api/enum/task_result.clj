
(ns sm_async_api.enum.task_result)

(def ^:const NEXT-ACTION  "0 - Get next action from channel and execute."  0)

(def ^:const RETRY-ACTION  "1 - Execute current action again."  1)

(def ^:const EXIT-THREAD  "2 - Fatal error - shudown thread."  2)

(def ^:const SERVER-NOT-AVAILABLE  "3 - Fatal error - reschedule action and sleep for server-not-available-waiting."  3)







