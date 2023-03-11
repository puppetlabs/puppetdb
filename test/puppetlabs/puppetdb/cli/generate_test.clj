(ns puppetlabs.puppetdb.cli.generate-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.generate :as generate]))

(defn filter-edges
  ([exp-source-type exp-target-type edges]
   (filter-edges exp-source-type exp-target-type :contains edges))
  ([exp-source-type exp-target-type exp-relation edges]
   (filter
     (fn [{:keys [relation]
          {stype "type"} :source
          {ttype "type"} :target}]
       (and (= stype exp-source-type)
            (= ttype exp-target-type)
            (or (= exp-relation :any)
                (= relation exp-relation))))
     edges)))

(deftest generate-catalog-graph
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
        g (generate/generate-catalog-graph m c r 50)
        e (:edges g)]
    (testing "an edge to each resource (and class resource)"
      (is (= 14 (count e))))
    (testing "Stage main contains each class"
      (is (= 3 (count (filter-edges "Stage" "Class" e)))))
    (testing "There is an additional relation between two classes"
      (is (= 1 (count (filter-edges "Class" "Class" :any e)))))
    (testing "Classes contain rest of resources"
      (is (= 7 (count (filter-edges "Class" "File" e)))))))

(deftest generate-catalog
  (let [c (generate/generate-catalog "host-1" {:num-classes 2 :num-resources 10 :title-size 20 :resource-size 200 :additional-edge-percent 50})]
    (is (contains? c :certname))
    (testing "resource count matches num-resources options + main"
      (is (= 11 (count (:resources c)))))
    (testing "edge count matches num-resources option + 50%"
      (is (= 15 (count (:edges c)))))))
