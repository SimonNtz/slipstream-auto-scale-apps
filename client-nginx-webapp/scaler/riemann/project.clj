(defproject streams "3.11-SNAPSHOT"
  :description "Riemann streams for SlipStream."
  :url "http://sixsq.com"

  :license {:name "Apache License, Version 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "2.0.0"]
                 [cheshire "5.5.0"]
                 [riemann "0.2.11"]
                 [com.sixsq.slipstream/SlipStreamClientAPI-jar "3.10"]]
  :repositories [["sixsq" {:url      "http://nexus.sixsq.com/content/repositories/releases-community-rhel7"
                           :username "pass"
                           :password :env}]])
