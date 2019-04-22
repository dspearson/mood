(defproject mood "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "ISC"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.json "0.2.6"]
                 [ring/ring-core "1.7.1"]
                 [clj-time "0.15.0"]
                 [buddy/buddy-core "1.5.0"]
                 [buddy/buddy-hashers "1.3.0"]
                 [ring/ring-json "0.4.0"]
                 [org.xerial/sqlite-jdbc "3.25.2"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [selmer "1.12.5"]
                 [ring/ring-jetty-adapter "1.7.1"]]
  :plugins [[lein-ring "0.12.4"]]
  :ring {:handler mood.core/app}
  :main ^:skip-aot mood.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
