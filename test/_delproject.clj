(defproject sm-async-api "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.3.618"]
                 [bidi "2.1.6"]
                 [http-kit "2.5.3"]
                 [clj-http "3.12.0"]
                 [ring/ring-core "1.9.3"]
                 [ring "1.9.0"]
                 [org.clojure/tools.logging "1.1.0"]
                 [metosin/ring-http-response "0.9.2"]
                 [io.forward/yaml "1.0.10"]
                 [com.taoensso/timbre "5.1.2"]
                 [ring-middleware-format "0.7.4" :exclusions [ring]]
                 [ring/ring-json "0.5.1"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [com.h2database/h2 "1.4.200"]
                 [com.taoensso/timbre "5.1.2"]
                 [buddy/buddy-core "1.10.1"]
                 [clj-time "0.15.2"]]
  :main ^:skip-aot sm-async-api.core

  :target-path "target/%s"

  :test-selectors {:dal :dal}
  :profiles {:uberjar
             {:aot :all
              :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :debug-repl
             {;:resource-paths [#=(
                                  ;eval (System/getenv "PATH_TO_TOOLS_JAR")
               ;                   str "/Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home/lib/tools.jar")]
              :dependencies [[org.clojure/clojure "1.8.0"] 
                             [debug-middleware "0.5.4"]]
              :repl-options {:nrepl-middleware [debug-middleware.core/debug-middleware]}}})
