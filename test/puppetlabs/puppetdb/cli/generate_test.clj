(ns puppetlabs.puppetdb.cli.generate-test
  (:require [clojure.java.shell :as shell]
            [clojure.set :as cset]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.generate :as generate]
            [puppetlabs.puppetdb.nio :refer [get-path]]))

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

(deftest generate-resources
  (let [resources (generate/generate-resources 100 500 20)
        key-weight 39 ;; size of base resource parameter keys which aren't stored in db
        footprints (map #(- (generate/weigh %) key-weight) resources)
        total-footprint (reduce + footprints)
        avg-footprint (quot total-footprint (count resources))]
    (is (= 100 (count resources)))
    (is (< 400 avg-footprint 600))
    (is (< 40000 total-footprint  60000))))

(deftest add-blob
  (let [catalog (generate/generate-catalog "host-1" {:num-classes 2 :num-resources 10 :title-size 20 :resource-size 200 :additional-edge-percent 50})
        c-with-blob (generate/add-blob catalog 100)]
    (testing "catalog is modified"
      (is (not= catalog c-with-blob))
      (is (< (generate/weigh catalog) 6000))
      (is (> (generate/weigh c-with-blob) 50000)))
    (testing "blob parameter is added"
      (let [resources (:resources c-with-blob)
            resources-with-blobs (filter
                                   (fn [rs]
                                     (let [param-keys (keys (get rs "parameters"))]
                                       (some #(string/starts-with? % "content_blob_") param-keys)))
                                   resources)
            resource-with-blob (first resources-with-blobs)
            content-blob-params (->> (get resource-with-blob "parameters")
                                     (filter (fn [[k _]] (string/starts-with? k "content_blob_"))))]
        (is (= (count resources-with-blobs) 1))
        (is (= (count content-blob-params) 1))
        (is (> (count (first (vals content-blob-params))) 50000))))))

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
                 :blob-count 0
                 :blob-size 100
                 :output-dir (.toString tmpdir)
                 :silent true}
        additional-edge-% (/ additional-edge-percent 100.0)
        num-edges (+ num-resources (int (* num-resources additional-edge-%)))]
    (testing "without blobs"
      (try
        (generate/generate options)
        (let [catalog-dir (.toFile (.resolve tmpdir "catalogs"))
              catalogs (map #(json/parse-string (slurp %)) (.listFiles catalog-dir))
              total-weight (->> catalogs (map generate/weigh) (reduce +))]
          (testing "generation of files"
            (is (< 250000 total-weight 500000))
            (doseq [cat catalogs]
              (is (= (+ num-resources 1) (count (get cat "resources"))))
              (is (= num-edges (count (get cat "edges"))))
              (is (< 50000 (generate/weigh cat) 100000))
              (let [resources (get cat "resources")
                    ;; Ignore the size of the base resource keys as they are
                    ;; not stored in the database.
                    footprints (sort
                                 #(< (:w %1) (:w %2))
                                 (map (fn [r]
                                        (let [key-weight (reduce + (map count (keys r)))]
                                          {:r r
                                           :w (- (generate/weigh r) key-weight)}))
                                      resources))
                    avg-resource-footprint (quot (reduce + (map :w footprints)) (count resources))
                    min-footprint (first footprints)
                    max-footprint (last footprints)]
                (is (< (* resource-size 0.5) avg-resource-footprint (* resource-size 1.5)))
                ;; Class resources aren't varied much.
                (is (< 25 (:w min-footprint) 150) (format "Odd min-footprint %s" min-footprint))
                (is (< resource-size (:w max-footprint) (* resource-size 2.5)) (format "Odd max-footprint %s" max-footprint))))))
        (finally (shell/sh "rm" "-rf" (.toString tmpdir)))))
    (testing "with blobs"
      (try
        (shell/sh "mkdir" (.toString tmpdir))
        (generate/generate (merge options {:blob-count 1}))
        (let [catalog-dir (.toFile (.resolve tmpdir "catalogs"))
              catalogs (map #(json/parse-string (slurp %)) (.listFiles catalog-dir))]
          (testing "generation of files"
            (doseq [cat catalogs]
                (is (= (+ num-resources 1) (count (get cat "resources"))))
                (is (= num-edges (count (get cat "edges"))))
                (is (> (generate/weigh cat) 100000)))
            (let [total-weight (->> catalogs (map generate/weigh) (reduce +))]
              (is (> total-weight 700000)))))
        (finally (shell/sh "rm" "-rf" (.toString tmpdir)))))
    (testing "distributed with blobs"
      (try
        (shell/sh "mkdir" (.toString tmpdir))
        (generate/generate (merge options {:blob-count 2 :random-distribution true}))
        (let [catalog-dir (.toFile (.resolve tmpdir "catalogs"))
              catalogs (map #(json/parse-string (slurp %)) (.listFiles catalog-dir))]
          (testing "generation of files"
            (doseq [cat catalogs]
              (let [resource-count (count (get cat "resources"))
                    edge-count (count (get cat "edges"))]
                (is (< 1 resource-count (* num-resources 2)))
                (is (<= resource-count edge-count (* resource-count 2)))))
            (let [total-weight (->> catalogs (map generate/weigh) (reduce +))]
              (is (> total-weight 900000)))))
        (finally (shell/sh "rm" "-rf" (.toString tmpdir)))))))

(deftest summarize
  (let [num-classes 10
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
                 :blob-count 0
                 :blob-size 100}
        data (generate/generate-data options (get-path "/dev/null"))
        output (with-out-str (generate/summarize data))]
    (is (re-find #":catalogs: 5\b" output))
    (is (re-find #":facts: 0\b" output))))

(deftest generate-fact-values
  (let []))
