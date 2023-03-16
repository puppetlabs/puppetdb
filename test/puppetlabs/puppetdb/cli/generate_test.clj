(ns puppetlabs.puppetdb.cli.generate-test
  (:require [clojure.java.shell :as shell]
            [clojure.set :as cset]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.cheshire :as json]
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
        g (generate/generate-catalog-graph m c r 0)
        e (:edges g)]
    (testing "an edge to each resource (and class resource)"
      (is (= 10 (count e))))
    (testing "Stage main contains each class"
      (is (= 3 (count (filter-edges "Stage" "Class" e)))))
    (testing "Classes contain rest of resources"
      (is (= 7 (count (filter-edges "Class" "File" e)))))
    (testing "With a percentage of additional edges"
      (let [g (generate/generate-catalog-graph m c r 50)
            e (:edges g)]
        (testing "there is a containment edge for each class and resource and an additional % of classes and resources have an extra edge (rounded down)"
          (is (= 14 (count e))))
        (testing "Stage main contains each class"
          (is (= 3 (count (filter-edges "Stage" "Class" e)))))
        (testing "There is an additional relation between two classes"
          (is (= 1 (count (filter-edges "Class" "Class" :any e)))))
        (testing "Classes contain rest of resources"
          (is (= 7 (count (filter-edges "Class" "File" e)))))
        (testing "There are any additional three non-containment edges between resources"
          (let [extras (->> (cset/union
                              (set (filter-edges "Class" "File" :any e))
                              (set (filter-edges "File" "Class" :any e))
                              (set (filter-edges "File" "File" :any e)))
                            (filter #(not= (:relation %) :contains)))]
            (is (= 3 (count extras)))))))))

(deftest generate-catalog
  (let [c (generate/generate-catalog "host-1" {:num-classes 2 :num-resources 10 :title-size 20 :resource-size 200 :additional-edge-percent 50})]
    (is (contains? c :certname))
    (testing "resource count matches num-resources options + main"
      (is (= 11 (count (:resources c)))))
    (testing "edge count matches num-resources option + 50%"
      (is (= 15 (count (:edges c)))))))

(defmulti weigh class)
(defmethod weigh clojure.lang.IPersistentMap
  [mp]
  (reduce (fn [size [k v]]
            (+ size (count k) (weigh v)))
          0, mp))
(defmethod weigh clojure.lang.IPersistentVector
  [vc]
  (reduce (fn [size e]
            (+ size (weigh e)))
          0, vc))
(defmethod weigh String [s] (count s))
(defmethod weigh Number [_] 4)
(defmethod weigh Boolean [_] 1)
(defmethod weigh :default
  [what] (throw (Exception. (format "Don't know how to weigh a %s of type %s" what (type what)))))

(deftest generate-resources
  (let [resources (generate/generate-resources 100 500 20)
        key-weight 39 ;; size of base resource parameter keys which aren't stored in db
        footprints (map #(- (weigh %) key-weight) resources)
        total-footprint (reduce + footprints)
        avg-footprint (quot total-footprint (count resources))]
    (is (= 100 (count resources)))
    (is (< 400 avg-footprint 600))
    (is (< 40000 total-footprint  60000))
    ))

(deftest generate
  (let [tmpdir (generate/create-temp-dir)
        num-classes 10
        num-resources 100
        title-size 20
        resource-size 500
        additional-edge-percent 50
        options {:num-hosts 5
                 :num-classes num-classes
                 :num-resources num-resources
                 :title-size title-size
                 :resource-size resource-size
                 :additional-edge-percent additional-edge-percent
                 :output-dir (.toString tmpdir)}
        additional-edge-% (/ additional-edge-percent 100.0)
        num-edges (+ num-resources (int (* num-resources additional-edge-%)))]
    (try
      (generate/generate options)
      (let [catalog-dir (.toFile (.resolve tmpdir "catalogs"))]
        (testing "generation of files"
          (doseq [f (.listFiles catalog-dir)]
            (let [cat (json/parse-string (slurp f))]
              (is (= (+ num-resources 1) (count (get cat "resources"))))
              (is (= num-edges (count (get cat "edges"))))
              (let [resources (get cat "resources")
                    ;; Ignore the size of the base resource keys as they are
                    ;; not stored in the database.
                    footprints (sort
                                 #(< (:w %1) (:w %2))
                                 (map (fn [r]
                                        (let [key-weight (reduce + (map count (keys r)))]
                                          {:r r
                                           :w (- (weigh r) key-weight)}))
                                      resources))
                    avg-resource-footprint (quot (reduce + (map :w footprints)) (count resources))
                    min-footprint (first footprints)
                    max-footprint (last footprints)]
                (is (< (* resource-size 0.5) avg-resource-footprint  (* resource-size 1.5)))
                ;; Class resources aren't varied much.
                (is (< 25 (:w min-footprint) 150) (format "Odd min-footprint %s" min-footprint))
                (is (< resource-size (:w max-footprint) (* resource-size 2.5)) (format "Odd max-footprint %s" max-footprint))
                )))))
      (finally (shell/sh "rm" "-rf" (.toString tmpdir))))))
