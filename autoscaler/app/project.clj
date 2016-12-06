(defproject streams "3.17-SNAPSHOT"
  :description "Riemann streams for SlipStream."
  :url "http://sixsq.com"

  :license {:name "Apache License, Version 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "2.0.0"]
                 [cheshire "5.5.0"]
                 [riemann "0.2.11"]]
  :repositories [["sixsq" {:url      "http://nexus.sixsq.com/content/repositories/releases-community-rhel7"
                           :username "pass"
                           :password :env}]])
