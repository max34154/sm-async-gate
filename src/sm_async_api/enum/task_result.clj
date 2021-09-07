
(ns sm_async_api.enum.task_result)

(def ^:const NEXT-ACTION  "Get next action from channel and execute."  0)

(def ^:const RETRY-ACTION  "Execute current action again."  1)

(def ^:const EXIT-THREAD  "Fatal error - shudown thread."  2)







