(ns puppetlabs.puppetdb.cli.generate-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.generate :as generate]))

(deftest generate-edges
  (let [m {"type" "Stage" "title" "main"}
        c [{"type" "Class" "title" "1"}
           {"type" "Class" "title" "2"}
           {"type" "Class" "title" "3"}]
        r [{"type" "File" "title" "1"}
           {"type" "File" "title" "2"}
           {"type" "File" "title" "3"}
           {"type" "File" "title" "4"}
           {"type" "File" "title" "5"}
           {"type" "File" "title" "6"}
           {"type" "File" "title" "7"}]
        e (generate/generate-edges m c r)]
    (testing "an edge to each resource (and class resource)"
      (is (= 10 (count e))))
    (let [edge-test (fn [exp-source-type exp-target-type edges]
                      (filter
                        (fn [{:keys [relationship]
                             {stype "type"} :source
                             {ttype "type"} :target}]
                          (and (= stype exp-source-type) (= ttype exp-target-type) (= relationship "contains")))
                        edges))]
      (testing "Stage main contains each class"
        (is (= 3 (count (edge-test "Stage" "Class" e)))))
      (testing "Classes contain rest of resources"
        (is (= 7 (count (edge-test "Class" "File" e))))))))

(deftest generate-catalog
  (let [c (generate/generate-catalog "host-1" {:num-classes 2 :num-resources 10 :title-size 20 :resource-size 200})]
    (is (contains? c :certname))
    (testing "resource count matches num-resources options + main"
      (is (= 11 (count (:resources c)))))
    (testing "edge count matches num-resources option"
      (is (= 10 (count (:edges c)))))))
