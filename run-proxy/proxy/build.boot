(def +version+ "3.16-SNAPSHOT")

(set-env!
  :project 'com.sixsq.slipstream/SlipStreamRunProxyServer-jar
  :version +version+
  :edition "community"

  :dependencies '[[org.clojure/clojure "1.8.0"]
                  [org.martinklepsch/boot-gzip "0.1.2"]
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

                    [clj-http]

                    [com.sixsq.slipstream/SlipStreamClientAPI-jar]

                    [compojure]
                    [aleph]
                    [environ]
                    [ring/ring-json]
                    [ring/ring-defaults]
                    [http-kit]

                    [adzerk/boot-test]
                    [tolitius/boot-check]
                    [pandeiro/boot-http]]))))

(require
  '[adzerk.boot-test :refer [test]]
  '[pandeiro.boot-http :refer [serve]]
  '[tolitius.boot-check :refer [with-yagni with-eastwood with-kibit with-bikeshed]])

(set-env!
  :source-paths #{"dev-resources" "test"}
  :resource-paths #{"src" "resources"})

(task-options!
  pom {:project (get-env :project)
       :version (get-env :version)}
  uber {:exclude-scope #{"test"}
        :exclude       #{#".*/pom.xml"
                         #"META-INF/.*\.SF"
                         #"META-INF/.*\.DSA"
                         #"META-INF/.*\.RSA"}}
  serve {:handler 'sixsq.slipstream.runproxy.server/app
         :reload  true}
  watch {:verbose true})

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
           (aot :namespace #{'sixsq.slipstream.runproxy.main})
           (uber)
           (jar :main 'sixsq.slipstream.runproxy.main)))

(deftask run
         "runs ring app and watches for changes"
         []
         (comp
           (watch)
           (pom)
           (aot :namespace #{'sixsq.slipstream.runproxy.main})
           (serve)))

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

