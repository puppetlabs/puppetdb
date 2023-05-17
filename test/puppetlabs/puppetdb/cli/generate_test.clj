(ns puppetlabs.puppetdb.cli.generate-test
  (:require [clojure.java.shell :as shell]
            [clojure.set :as cset]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.generate :as generate]
            [puppetlabs.puppetdb.facts :as facts]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.time :as time]))

;; Avg bytes in the generate-package-inventory() package array.
;; Mean 12 chars package name, 6 chars provider, mean 6 version."
(def avg-package-weight 25)

(defn filter-edges
  ([exp-source-type exp-target-type edges]
   (filter-edges exp-source-type exp-target-type :contains edges))
  ([exp-source-type exp-target-type exp-relation edges]
   (filter
     (fn [{:keys [relation]
          {stype :type} :source
          {ttype :type} :target}]
       (and (= stype exp-source-type)
            (= ttype exp-target-type)
            (or (= exp-relation :any)
                (= relation exp-relation))))
     edges)))

(deftest generate-catalog-graph-test
  (let [m {:type "Stage" :title "main"}
        c [{:type "Class" :title "1"}
           {:type "Class" :title "2"}
           {:type "Class" :title "3"}]
        r [{:type "File" :title "1"}
           {:type "File" :title "2"}
           {:type "File" :title "3"}
           {:type "File" :title "4"}
           {:type "File" :title "5"}
           {:type "File" :title "6"}
           {:type "File" :title "7"}]
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

(deftest generate-catalog-test
  (let [c (generate/generate-catalog "host-1" {:num-classes 2 :num-resources 10 :title-size 20 :resource-size 200 :additional-edge-percent 50})]
    (is (contains? c :certname))
    (testing "resource count matches num-resources options + main"
      (is (= 11 (count (:resources c)))))
    (testing "edge count matches num-resources option + 50%"
      (is (= 15 (count (:edges c)))))))

(deftest generate-resources-test
  (let [resources (generate/generate-resources 100 500 20)
        key-weight 39 ;; size of base resource parameter keys which aren't stored in db
        footprints (map #(- (generate/weigh %) key-weight) resources)
        total-footprint (reduce + footprints)
        avg-footprint (quot total-footprint (count resources))]
    (is (= 100 (count resources)))
    (is (< 400 avg-footprint 600))
    (is (< 40000 total-footprint  60000))))

(deftest add-blob-test
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
                                     (let [param-keys (keys (:parameters rs))]
                                       (some #(string/starts-with? % "content_blob_") param-keys)))
                                   resources)
            resource-with-blob (first resources-with-blobs)
            content-blob-params (->> (:parameters resource-with-blob)
                                     (filter (fn [[k _]] (string/starts-with? k "content_blob_"))))]
        (is (= (count resources-with-blobs) 1))
        (is (= (count content-blob-params) 1))
        (is (> (count (first (vals content-blob-params))) 50000))))))

(def default-test-options
  {:num-hosts 5
   :num-classes 10
   :num-resources 100
   :title-size 20
   :resource-size 500
   :additional-edge-percent 50
   :blob-count 0
   :blob-size 100
   :num-facts 500
   :total-fact-size 25
   :max-fact-depth 10
   :num-packages 1000
   :num-reports 10
   :high-change-reports-percent 5
   :high-change-resources-percent 80
   :low-change-reports-percent 20
   :low-change-resources-percent 5
   :exclude-unchanged-resources true
   :num-additional-logs 0
   :percent-add-report-logs 1
   :silent true})

(deftest generate-test
  (let [tmpdir (generate/create-temp-dir)
        options (merge default-test-options {:output-dir (.toString tmpdir)})
        num-hosts (:num-hosts options)
        num-resources (:num-resources options)
        resource-size (:resource-size options)
        num-facts (:num-facts options)
        total-fact-size (:total-fact-size options)
        num-packages (:num-packages options)
        additional-edge-% (/ (:additional-edge-percent options) 100.0)
        num-edges (+ num-resources (int (* num-resources additional-edge-%)))]
    (testing "without blobs"
      (try
        (generate/generate options)
        (let [catalog-dir (.toFile (.resolve tmpdir "catalogs"))
              catalogs (map #(json/parse-string (slurp %)) (.listFiles catalog-dir))
              total-weight (->> catalogs (map generate/weigh) (reduce +))]
          (testing "generation of catalog files"
            (is (= num-hosts (count catalogs)))
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
                (is (< 20 (:w min-footprint) 150) (format "Odd min-footprint %s" min-footprint))
                (is (< resource-size (:w max-footprint) (* resource-size 2.5)) (format "Odd max-footprint %s" max-footprint))))))
        (let [facts-dir (.toFile (.resolve tmpdir "facts"))
              factsets (map #(json/parse-string (slurp %)) (.listFiles facts-dir))
              total-weight (->> factsets (map generate/weigh) (reduce +))
              total-fact-size-in-bytes (* total-fact-size 1000)
              est-factset-weight (+ total-fact-size-in-bytes
                                    (* num-packages avg-package-weight))]
          (testing "generation of factset files"
            (is (= num-hosts (count factsets)))
            (is (< (* num-hosts est-factset-weight) total-weight (* num-hosts est-factset-weight 1.25)))
            (doseq [factset factsets]
              (let [facts (get factset "values")
                    leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))
                    weight (generate/weigh facts)]
                (is (= num-facts (count leaf-paths)))
                (is (<= total-fact-size-in-bytes weight (* total-fact-size-in-bytes 1.25)))))))
        (finally (shell/sh "rm" "-rf" (.toString tmpdir)))))
    (testing "with blobs"
      (try
        (shell/sh "mkdir" (.toString tmpdir))
        (generate/generate (merge options {:blob-count 1}))
        (let [catalog-dir (.toFile (.resolve tmpdir "catalogs"))
              catalogs (map #(json/parse-string (slurp %)) (.listFiles catalog-dir))]
          (testing "generation of catalog files"
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
          (testing "generation of catalog files"
            (doseq [cat catalogs]
              (let [resource-count (count (get cat "resources"))
                    edge-count (count (get cat "edges"))]
                (is (< 1 resource-count (* num-resources 2)))
                (is (<= resource-count edge-count (* resource-count 2)))))
            (let [total-weight (->> catalogs (map generate/weigh) (reduce +))]
              (is (> total-weight 900000)))))
        (let [facts-dir (.toFile (.resolve tmpdir "facts"))
              factsets (map #(json/parse-string (slurp %)) (.listFiles facts-dir))
              total-weight (->> factsets (map generate/weigh) (reduce +))
              total-fact-size-in-bytes (* total-fact-size 1000)
              est-factset-weight (+ total-fact-size-in-bytes
                                    (* num-packages avg-package-weight))]
          (testing "generation of factset files"
            (is (= num-hosts (count factsets)))
            (is (< (* num-hosts est-factset-weight 0.25) total-weight (* num-hosts est-factset-weight 3)))
            (doseq [factset factsets]
              (let [facts (get factset "values")
                    leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))
                    weight (generate/weigh facts)]
                (is (< (* num-facts 0.1) (count leaf-paths) (* num-facts 2)))
                ;; fact weight is also going to be affected by num-facts which
                ;; may end up generating more weight than requested...
                (is (< (* total-fact-size-in-bytes 0.1) weight (* total-fact-size-in-bytes 4)))))))
        (finally (shell/sh "rm" "-rf" (.toString tmpdir)))))))

(deftest summarize-test
  (let [options default-test-options
        data (generate/generate-data options (get-path "/dev/null"))
        output (with-out-str (generate/summarize data))]
    (is (re-find #":catalogs: 5\b" output))
    (is (re-find #":facts: 5\b" output))
    (is (re-find #":reports: 0\b" output))))

(deftest create-new-facts-test
  (let [facts (generate/create-new-facts 100 5)
        fact-paths (facts/facts->pathmaps facts)
        leaf-paths (generate/leaf-fact-paths fact-paths)
        max-depth (apply max (map #(count (:path_array %)) fact-paths))]
    (is (= 100 (count leaf-paths)))
    (is (<= max-depth 5))))

(deftest mutate-fact-values-test
  (let [facts (get (generate/load-baseline-factset) "values")
        leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))
        mutated (generate/mutate-fact-values facts 100 leaf-paths)
        mutated-leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps mutated))]
    (is (not= facts mutated))
    (is (= (count leaf-paths) (count mutated-leaf-paths)))))

(deftest delete-facts-test
  (let [facts (get (generate/load-baseline-factset) "values")
        leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))
        trimmed (generate/delete-facts facts 100 leaf-paths)
        trimmed-leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps trimmed))]
    (is (not= facts trimmed))
    (is (= (- (count leaf-paths) 100) (count trimmed-leaf-paths)))))

(deftest fatten-fact-values-test
  (let [facts (get (generate/load-baseline-factset) "values")
        initial-weight (generate/weigh facts)]
    (testing "fatten to same weight does nothing"
      (let [fattened (generate/fatten-fact-values facts initial-weight)]
        (is (= initial-weight (generate/weigh fattened)))))
    (testing "fatten to less than weight does nothing"
      (let [target-weight (- initial-weight 1000)
            fattened (generate/fatten-fact-values facts target-weight)]
        (is (= initial-weight (generate/weigh fattened)))))
    (testing "fatten to edge weight"
      (let [target-weight (+ initial-weight 1)
            fattened (generate/fatten-fact-values facts target-weight)]
        (is (= target-weight (generate/weigh fattened)))))
    (testing "fatten to greater weight"
      (let [target-weight (+ initial-weight 1000)
            fattened (generate/fatten-fact-values facts target-weight)]
        (is (= target-weight (generate/weigh fattened)))))))

(deftest generate-fact-values-test
  (testing "defaults"
    (let [num-facts 400
          max-fact-depth 7
          total-fact-size-in-bytes 10000
          options {}
          facts (generate/generate-fact-values num-facts max-fact-depth total-fact-size-in-bytes options)
          leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))
          weight (generate/weigh facts)]
      (is (= num-facts (count leaf-paths)))
      (is (<= total-fact-size-in-bytes weight (* total-fact-size-in-bytes 1.1)))))
  (testing "greater than baseline"
    (let [num-facts 500
          max-fact-depth 10
          total-fact-size-in-bytes 50000
          options {}
          facts (generate/generate-fact-values num-facts max-fact-depth total-fact-size-in-bytes options)
          leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))
          weight (generate/weigh facts)]
      (is (= num-facts (count leaf-paths)))
      (is (<= total-fact-size-in-bytes weight (* total-fact-size-in-bytes 1.25)))))
  (testing "less than baseline"
    (let [num-facts 100
          max-fact-depth 5
          total-fact-size-in-bytes 5000
          options {}
          facts (generate/generate-fact-values num-facts max-fact-depth total-fact-size-in-bytes options)
          leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))
          weight (generate/weigh facts)]
      (is (= num-facts (count leaf-paths)))
      (is (<= total-fact-size-in-bytes weight (* total-fact-size-in-bytes 1.1))))))

(deftest generate-package-inventory-test
  (let [packages (generate/generate-package-inventory 100)]
    (is (= 100 (count packages)))
    (is (<= 3 (count (set (map #(get % 1) packages))) 5))))

(deftest generate-factset-test
  (testing "without packages"
    (let [f (generate/generate-factset "host-1" {:num-facts 500 :max-fact-depth 7 :total-fact-size 25 :num-packages 0})
          facts (:values f)
          leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))]
      (is (= (:certname f) "host-1"))
      (is (= 500 (count leaf-paths)))
      (is (<= 25000 (generate/weigh facts) 27500))
      (is (nil? (:package_inventory f)))))
  (testing "with packages"
    (let [num-packages 100
          f (generate/generate-factset "host-1" {:num-facts 500 :max-fact-depth 7 :total-fact-size 25 :num-packages num-packages})
          facts (:values f)
          packages (:package_inventory f)
          est-packages-weight (* num-packages avg-package-weight)
          leaf-paths (generate/leaf-fact-paths (facts/facts->pathmaps facts))]
      (is (= 500 (count leaf-paths)))
      (is (<= (+ est-packages-weight 25000) (generate/weigh f) (+ est-packages-weight 27500)))
      (is (= 100 (count packages)))
      (is (<= 3 (count (set (map #(get % 1) packages))) 5)))))

(deftest vary-param-test
  (testing "no random distribution")
    (is (= 5 (generate/vary-param 5 false 0.25)))
  (testing "random distribution"
    (testing "positive"
      (is (< 0 (generate/vary-param 5 true 0.25) 10)))
    (testing "zero"
      (is (= 0 (generate/vary-param 0 true 0.25))))
    (testing "negative")
      (is (thrown-with-msg? ArithmeticException #"lowerb of 0 which is greater than mean of -5" (generate/vary-param -5 true 0.25)))))

(deftest generate-log-test
  (testing "from level and message"
    (let [log (generate/generate-log "info" "a message")]
      (is (= {:level "info" :message "a message" :source "Puppet" :tags #{"info"} :file nil :line nil}
             (dissoc log :time)))))
  (testing "from map"
    (let [log (generate/generate-log {:file "file.rb" :line 5 :tags ["one" "two"]})]
      (is (= {:file "file.rb" :line 5 :source "Puppet"}
             (dissoc log :time :level :message :tags)))
      (is (re-matches #"\A[A-Z][\w ]+\.\Z" (:message log)))
      (is (some #{(:level log)} ["info" "notice"]))
      (is (= #{"one" "two" (:level log)} (:tags log)))))
  (testing "from empty map"
    (let [log (generate/generate-log {})
          level (:level log)]
      (is (= {:file nil :line nil :tags #{level} :source "Puppet"}
             (dissoc log :level :time :message)))
      (is (some #{level} ["info" "notice"]))
      (is (re-matches #"\A[A-Z][\w ]+\.\Z" (:message log)))
      (is (= -1 (compare (:time log) (time/now)))))))

(deftest generate-report-logs-test
  (let [catalog (generate/generate-catalog "host-1" {:num-classes 2 :num-resources 10 :title-size 20 :resource-size 100 :additional-edge-percent 50})]
    (testing "with changed resources"
      (let [changed-resources (take 10 (shuffle (:resources catalog)))
            logs (generate/generate-report-logs catalog changed-resources)
            headers (take 5 logs)
            changes (take 10 (drop 5 logs))
            footer (last logs)]
        (is (= 16 (count logs)))
        (is (= ["Using" "Retrieving" "Retrieving" "Loading" "Applying"]
               (map #(-> % :message (string/split #" ") first) headers))
            "Headers are boilerplate.")
        (is (not-any? nil? (map #(:file %) changes)) "Resource params mapped to logs.")
        (is (string/starts-with? (:message footer) "Applied catalog in"))))
    (testing "without changed resources"
      (let [logs (generate/generate-report-logs catalog [])]
        (is (= 1 (count logs)))
        (is (string/starts-with? (-> logs first (get :message)) "Applied catalog in"))))))
