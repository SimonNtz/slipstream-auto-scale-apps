(ns sixsq.slipstream.runproxy.api-test
  (:require
    [clojure.test :refer :all]
    [sixsq.slipstream.runproxy.api :refer :all]))

(deftest test-scale-failure?
    (is (= true (scale-failure? {})))
    (is (= true (scale-failure? {:status 400})))
    (is (= true (scale-failure? {:status 400 :body "failure"})))
    (is (= true (scale-failure? {:status 200 :body "failure"})))
    (is (= false (scale-failure? {:status 200 :body "success"}))))
