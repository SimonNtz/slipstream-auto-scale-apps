(def +version+ "3.17-SNAPSHOT")

(set-env!
  :project 'com.sixsq.slipstream/SlipStreamRunProxyApi-jar
  :version +version+
  :edition "community"

  :dependencies '[[org.clojure/clojure "1.8.0"]
                  [sixsq/build-utils "0.1.4" :scope "test"]])

(require '[sixsq.build-fns :refer [merge-defaults
                                   sixsq-nexus-url
                                   lein-generate]])

(set-env!
  :repositories
  #(reduce conj % [["sixsq" {:url (sixsq-nexus-url)}]])
  :dependencies
  #(vec (concat %
                (merge-defaults
                  ['sixsq/default-deps (get-env :version)]
                  '[[org.clojure/clojure]
                    [org.clojure/core.async]
                    [org.clojure/tools.logging]
                    [org.clojure/tools.reader "1.0.0-beta3"]

                    [riemann "0.2.11"]

                    [clj-http]

                    [adzerk/boot-test]
                    [tolitius/boot-check]]))))

(require
  '[adzerk.boot-test :refer [test]]
  '[tolitius.boot-check :refer [with-yagni with-eastwood with-kibit with-bikeshed]])

(set-env!
  :source-paths #{"dev-resources" "test"}
  :resource-paths #{"src"})

(task-options!
  pom {:project (get-env :project)
       :version (get-env :version)})

(deftask run-tests
         "runs all tests and performs full compilation"
         []
         (comp
           (aot :all true)
           (test)))

(deftask build
         "build jar of service"
         []
         (comp
           (pom)
           (jar)))

(deftask mvn-test
         "run all tests of project"
         []
         (run-tests))

(deftask mvn-build
         "build full project through maven"
         []
         (comp
           (build)
           (install)
           (target)))

(deftask mvn-deploy
         "build full project through maven"
         []
         (comp
           (mvn-build)
           (push :repo "sixsq")))

