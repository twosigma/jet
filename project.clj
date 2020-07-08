(def jetty-version "9.4.20.v20190813")
(defproject twosigma/jet "0.7.10"
  :description "Jetty9 ring server adapter with WebSocket support"
  :url "https://github.com/mpenet/jet"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.mortbay.jetty.alpn/alpn-boot "8.1.13.v20181017"
                  :prepend true]
                 [org.clojure/core.async "0.2.374"]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.http2/http2-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-client ~jetty-version]
                 [org.eclipse.jetty/jetty-client ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-java-client ~jetty-version]
                 [org.eclipse.jetty/jetty-alpn-java-server ~jetty-version]
                 [org.eclipse.jetty.alpn/alpn-api "1.1.3.v20160715"]
                 [org.eclipse.jetty.http2/http2-common ~jetty-version
                  :exclusions [org.eclipse.jetty.http2/http2-hpack]]
                 [org.eclipse.jetty.http2/http2-http-client-transport ~jetty-version]
                 [org.eclipse.jetty.http2/http2-client ~jetty-version]
                 [cheshire "5.5.0"]]

  :plugins [[info.sunng/lein-bootclasspath-deps "0.3.0"]]

  :repositories {"sonatype" {:url "http://oss.sonatype.org/content/repositories/releases"
                             :snapshots false
                             :releases {:checksum :fail :update :always}}
                 "sonatype-snapshots" {:url "http://oss.sonatype.org/content/repositories/snapshots"
                                       :snapshots true
                                       :releases {:checksum :fail :update :always}}}
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.0"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0-alpha4"]]}
             :master {:dependencies [[org.clojure/clojure "1.7.0-master-SNAPSHOT"]]}
             :dev  {:dependencies [[ring/ring-core "1.3.0"
                                    :exclusions [javax.servlet/servlet-api]]
                                   [ring/ring-servlet "1.3.0"
                                    :exclusions [javax.servlet/servlet-api]]
                                   [codox "0.8.10"]
                                   [org.slf4j/slf4j-log4j12 "1.7.3"]]}}
  :jar-exclusions [#"log4j.properties"]
  :aliases {"all" ["with-profile" "dev:dev,1.5:dev,1.6:dev,1.7:dev,master"]}
  :codox {:src-dir-uri "https://github.com/mpenet/jet/blob/master/"
          :src-linenum-anchor-prefix "L"
          :output-dir "doc"
          :defaults {:doc/format :markdown}}
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :resource-paths ["src/resources"]
  :global-vars {*warn-on-reflection* true})
