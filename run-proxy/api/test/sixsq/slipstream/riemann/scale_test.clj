(ns sixsq.slipstream.riemann.scale-test
  (:require
    [clojure.test :refer :all]
    [sixsq.slipstream.riemann.scale :as rs]))

(deftest test-set-service-tags?
  (is (= #{} rs/*service-tags*))
  (let [_ (rs/set-service-tags)]
    (is (= #{} rs/*service-tags*)))
  (let [_ (rs/set-constraint (assoc rs/constraint-template :service-tags []))
        _ (rs/set-service-tags)]
    (is (= #{} rs/*service-tags*)))
  (let [_ (rs/set-constraint (assoc rs/constraint-template :service-tags ["foo"]))
        _ (rs/set-service-tags)]
    (is (= #{"foo"} rs/*service-tags*))))

