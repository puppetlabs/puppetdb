(ns puppetlabs.puppetdb.utils.metrics-test
  (:require [puppetlabs.puppetdb.utils.metrics :refer :all]
            [clojure.test :refer :all]
            [metrics.timers :refer [timer]]))

(deftest test-multitime-macro
  (testing "should update all supplied timers"
    (let [timers (mapv timer ["t1" "t2" "t3"])]
      (doseq [t timers]
        (is (zero? (number-recorded t))))
      (is (= 6 (multitime! timers (+ 1 2 3))))
      (doseq [t timers]
        (is (= 1 (number-recorded t)))))))
