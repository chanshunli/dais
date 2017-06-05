(defproject infihis/dais "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :pedantic? :abort
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [honeysql "0.8.2"]
                 [nilenso/honeysql-postgres "0.2.2"]
                 [org.postgresql/postgresql "42.1.1"]
                 [com.taoensso/timbre "4.10.0"]
                 [clj-time "0.13.0"]
                 [cheshire "5.7.1"]
                 [cc.qbits/spandex "0.3.11" :exclusions [org.clojure/clojure]]
                 [org.clojure/java.jdbc "0.6.2-alpha3"]
                 [org.clojure/tools.reader "1.0.0-beta4"]
                 [me.raynes/conch "0.8.0"]
                 [instaparse "1.4.7"]])
