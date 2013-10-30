(ns com.puppetlabs.puppetdb.test.scf.hash
  (:require [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.scf.hash :refer :all]
            [com.puppetlabs.random :as random]
            [clojure.math.combinatorics :refer (combinations subsets)]
            [com.puppetlabs.puppetdb.examples :refer [catalogs]]
            [com.puppetlabs.puppetdb.catalog.utils :as catutils]
            [com.puppetlabs.puppetdb.examples.reports :refer [reports]]
            [com.puppetlabs.puppetdb.report.utils :as reputils]
            [clj-time.core :as time]
            [com.puppetlabs.puppetdb.scf.storage :as store]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [fs.core :as fs]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.utils :as utils]))

(deftest hash-computation
  (testing "generic-identity-*"
    (doseq [func [generic-identity-string generic-identity-hash]]
      (testing "should return the same value for recursive misordered hashes that are equal"
        (let [unsorted {:f 6 :c 3 :z 26 :a 1 :l 11 :h 7 :e 5 :m 12 :b 2 :d 4 :g 6}
              sorted   (into (sorted-map) unsorted)
              reversed (into (sorted-map-by (fn [k1 k2] (compare k2 k1))) unsorted)]
          (is (= (func {:foo unsorted})
                 (func {:foo sorted})
                 (func {:foo reversed})))))
      (testing "should not match when two different data structures are supplied"
        (let [a {:f 6 :c 3 :z 26 :a 1 :l 11 :h 7 :e 5 :m 12 :b 2 :d 4 :g 6}
              b {:z 26 :i 8 :j 9 :k 10 :l 11 :m 12 :n 13}]
          (is (not (= (func a)
                      (func b)))))))

    (testing "should return the expected string in a sorted and predictable way"
      (let [input {:b "asdf" :a {:z "asdf" :k [:z {:z #{:a {:a [1 2] :b 2}} :a 1} :c] :a {:m "asdf" :b "asdf"}}}]
        (testing "generic-identity-string"
          (let [output (generic-identity-string ["Type" "title" {:foo input}])]
            (is (= output
                   "[\"Type\",\"title\",{\"foo\":{\"a\":{\"a\":{\"b\":\"asdf\",\"m\":\"asdf\"},\"k\":[\"z\",{\"a\":1,\"z\":[\"a\",{\"a\":[1,2],\"b\":2}]},\"c\"],\"z\":\"asdf\"},\"b\":\"asdf\"}}]"))))

        (testing "generic-identity-hash"
          (let [output (generic-identity-hash ["Type" "title" {:foo input}])]
            (is (= output
                   "c62dab030cfe8c25a4832c5c6302b7f0041264ba")))))))

  (testing "resource-identity-hash"
    (testing "should error on bad input"
      (is (thrown? AssertionError (resource-identity-hash nil)))
      (is (thrown? AssertionError (resource-identity-hash []))))

    (testing "should be equal for the base case"
      (is (= (resource-identity-hash {})
             (resource-identity-hash {}))))

    (testing "shouldn't change for identical input"
      (doseq [i (range 10)
              :let [r (random/random-kw-resource)]]
        (is (= (resource-identity-hash r)
               (resource-identity-hash r)))))

    (testing "shouldn't change for equivalent input"
      (is (= (resource-identity-hash {:foo 1 :bar 2})
             (resource-identity-hash {:bar 2 :foo 1})))
      (is (= (resource-identity-hash {:tags #{1 2 3}})
             (resource-identity-hash {:tags #{3 2 1}}))))

    (testing "should be different for non-equivalent resources"
      ;; Take a population of 5 resource, put them into a set to make
      ;; sure we only care about a population of unique resources, take
      ;; any 2 elements from that set, and those 2 resources should
      ;; have different hashes.
      (let [candidates (set (repeatedly 5 random/random-kw-resource))
            pairs      (combinations candidates 2)]
        (doseq [[r1 r2] pairs]
          (is (not= (resource-identity-hash r1)
                    (resource-identity-hash r2))))))

    (testing "should return the same predictable string"
      (is (= (resource-identity-hash {:foo 1 :bar 2})
             "b4199f8703c5dc208054a62203db132c3d12581c"))))

  (testing "catalog-similarity-hash"
    (let [sample {:certname  "foobar.baz"
                  :resources {:foo {:type "Type" :title "foo" :parameters {:a 1 :c 3 :b {:z 26 :c 3}} :file "/tmp" :line 3 :tags ["foo" "bar"]}}
                  :edges     [{:source {:type "Type" :title "foo"} :target {:type "File" :title "/tmp"}}]}]

      (testing "shouldn't change for identical input"
        (is (= (catalog-similarity-hash sample)
               (catalog-similarity-hash sample))))

      (testing "should return the same predictable string"
        (is (= (catalog-similarity-hash sample)
               "40f42c42bcd81ae28ab306ab64498f0bd6674ce6")))))

  (testing "resource-event-identity-string"
    (let [sample {:resource-type  "Type"
                  :resource-title "foo"
                  :property       "name"
                  :timestamp      "foo"
                  :status         "skipped"
                  :old-value      "baz"
                  :new-value      "foo"
                  :message        "Name changed from baz to foo"}]

      (testing "shouldn't change for identical input"
        (is (= (resource-event-identity-string sample)
               (resource-event-identity-string sample))))

      (testing "should return the same predictable string"
        (is (= (resource-event-identity-string sample)
               "{\"file\":null,\"line\":null,\"message\":\"Name changed from baz to foo\",\"new-value\":\"foo\",\"old-value\":\"baz\",\"property\":\"name\",\"resource-title\":\"foo\",\"resource-type\":\"Type\",\"status\":\"skipped\",\"timestamp\":\"foo\"}")))))

  (testing "catalog-resource-identity-format"
    (let [sample {:type "Type"
                  :title "title"
                  :parameters {:d {:b 2 :c [:a :b :c]} :c 3 :a 1}
                  :exported false
                  :file "/tmp/zzz"
                  :line 15}]

      (testing "should return sorted predictable string output"
        (is (= (sorted-map :type "Type"
                           :title "title"
                           :parameters (sorted-map :d (sorted-map :b 2 :c [:a :b :c])
                                                   :c 3
                                                   :a 1)
                           :exported false
                           :file "/tmp/zzz"
                           :line 15)
               (catalog-resource-identity-format sample))))

      (testing "should ignore extra key/values"
        (is (= (sorted-map :type "Type"
                           :title "title")
               (catalog-resource-identity-format {:type "Type"
                                                  :title "title"
                                                  :foo "bar"}))))))

  (testing "report-identity-hash"
    (let [sample {:certname "foobar.baz"
                  :puppet-version "3.2.1"
                  :report-format 1
                  :configuration-version "asdffdsa"
                  :start-time "2012-03-01-12:31:11.123"
                  :end-time   "2012-03-01-12:31:31.123"
                  :resource-events [
                                    {:type "Type"
                                     :title "title"
                                     :parameters {:d {:b 2 :c [:a :b :c]} :c 3 :a 1}
                                     :exported false :file "/tmp/zzz"
                                     :line 15}]}]

      (testing "should return sorted predictable string output"
        (is (= (report-identity-hash sample)
               "3504b79ff27eb17f4b83bf597d3944bb91cbb1ab")))

      (testing "should return the same value twice"
        (is (= (report-identity-hash sample)
               (report-identity-hash sample)))))))

(deftest catalog-dedupe
  (testing "Catalogs with the same metadata but different content should have different hashes"
    (let [catalog       (:basic catalogs)
          hash          (catalog-similarity-hash catalog)
          ;; List of all the tweaking functions
          chaos-monkeys [catutils/add-random-resource-to-catalog
                         catutils/mod-resource-in-catalog
                         catutils/mod-resource-metadata-in-catalog
                         catutils/add-random-edge-to-catalog
                         catutils/swap-edge-targets-in-catalog]
          ;; Function that will apply a random tweak function
          apply-monkey  #((rand-nth chaos-monkeys) %)]

      (is (not= hash (catalog-similarity-hash (catutils/add-random-resource-to-catalog catalog))))
      (is (not= hash (catalog-similarity-hash (catutils/mod-resource-in-catalog catalog))))
      (is (not= hash (catalog-similarity-hash (catutils/mod-resource-metadata-in-catalog catalog))))
      (is (not= hash (catalog-similarity-hash (catutils/add-random-edge-to-catalog catalog))))

      ;; Do the following 100 times: pick up to 10 tweaking functions,
      ;; successively apply them all to the original catalog, and
      ;; verify that the hash of the resulting catalog is the same as
      ;; the hash of the original catalog
      (doseq [nmonkeys (repeatedly 100 #(inc (rand-int 10)))
              :let [tweaked-catalog (nth (iterate apply-monkey catalog) nmonkeys)
                    tweaked-hash    (catalog-similarity-hash tweaked-catalog)]]
        (if (= catalog tweaked-catalog)
          (is (= hash tweaked-hash)
            (str catalog "\n has hash: " hash "\n and \n" tweaked-catalog "\n has hash: " tweaked-hash))
          (is (not= hash tweaked-hash)
            (str catalog "\n has hash: " hash "\n and \n" tweaked-catalog "\n has hash: " tweaked-hash))))))

  (testing "Catalogs with different metadata but the same content should have the same hashes"
    (let [catalog            (:basic catalogs)
          hash               (catalog-similarity-hash catalog)
          ;; Functions that tweak various attributes of a catalog
          tweak-api-version  #(assoc % :api-version (inc (:api-version %)))
          tweak-version      #(assoc % :version (str (:version %) "?"))
          tweak-puppetdb-version #(assoc % :puppetdb-version (inc (:puppetdb-version %)))
          ;; List of all the tweaking functions
          chaos-monkeys      [tweak-api-version tweak-version tweak-puppetdb-version]
          ;; Function that will apply a random tweak function
          apply-monkey       #((rand-nth chaos-monkeys) %)]

      ;; Do the following 100 times: pick up to 10 tweaking functions,
      ;; successively apply them all to the original catalog, and
      ;; verify that the hash of the resulting catalog is the same as
      ;; the hash of the original catalog
      (doseq [nmonkeys (repeatedly 100 #(inc (rand-int 10)))
              :let [tweaked-catalog (nth (iterate apply-monkey catalog) nmonkeys)
                    tweaked-hash    (catalog-similarity-hash tweaked-catalog)]]
        (is (= hash tweaked-hash)
          (str catalog "\n has hash: " hash "\n and \n" tweaked-catalog "\n has hash: " tweaked-hash))))))

(deftest report-dedupe
  (let [report (:basic reports)
        report-hash (report-identity-hash report)]
    (testing "Reports with the same metadata but different events should have different hashes"
      (is (= report-hash (report-identity-hash report)))
      (is (not= report-hash (report-identity-hash (reputils/add-random-event-to-report report))))
      (is (not= report-hash (report-identity-hash (reputils/mod-event-in-report report))))
      (is (not= report-hash (report-identity-hash (reputils/remove-random-event-from-report report)))))

    (testing "Reports with different metadata but the same events should have different hashes"
      (let [mod-report-fns [#(assoc % :certname (str (:certname %) "foo"))
                            #(assoc % :puppet-version (str (:puppet-version %) "foo"))
                            #(assoc % :report-format (inc (:report-format %)))
                            #(assoc % :configuration-version (str (:configuration-version %) "foo"))
                            #(assoc % :start-time (str (:start-time %) "foo"))
                            #(assoc % :end-time (str (:start-time %) "foo"))]]
        (doseq [mod-report-fn mod-report-fns]
          (is (not= report-hash (report-identity-hash (mod-report-fn report)))))))))

(defn persist-catalog
  "Adds the certname and full catalog to the database, returns the catalog map with
   the generated as as `:persisted-hash`"
  [{:keys [certname] :as catalog}]
  (store/add-certname! certname)
  (let [persisted-hash (store/add-catalog! catalog)]
    (store/associate-catalog-with-certname! persisted-hash certname (time/now))
    (assoc catalog :persisted-hash persisted-hash)))

(defn find-file
  "Finds files in `dir` with the given `suffix`. Useful for the debugging
   files that include a UUID in the prefix of the file name."
  [^String suffix dir]
  (first
   (for [f (fs/list-dir dir)
         :when (.endsWith f suffix)]
     (str dir "/" f))))

(def ^{:doc "Reads a catalog debugging clojure file from the file system."}
  slurp-clj
  (comp read-string slurp find-file))

(def ^{:doc "Reads/parses a JSON catalog debugging file from the file system."}
  slurp-json
  (comp json/parse-string slurp find-file))

(deftest debug-catalog-output
  (fixt/with-test-db
    (fn []
      (let [debug-dir (fs/absolute-path (tu/temp-dir))
            {:keys [persisted-hash] :as orig-catalog} (persist-catalog (:basic catalogs))
            new-catalog (assoc-in (:basic catalogs)
                                  [:resources {:type "File"
                                               :title "/etc/foobar/bazv2"}]
                                  {:type "File"
                                   :title "/etc/foobar/bazv2"})
            new-hash (catalog-similarity-hash new-catalog)]

        (is (nil? (fs/list-dir debug-dir)))
        (debug-catalog debug-dir new-hash new-catalog)
        (is (= 5 (count (fs/list-dir debug-dir))))

        (let [{old-edn-res :resources
               old-edn-edges :edges
               :as old-edn} (slurp-clj "old-catalog.edn" debug-dir)
              {new-edn-res :resources
               new-edn-eges :edges
               :as new-edn} (slurp-clj "new-catalog.edn" debug-dir)
              {old-json-res "resources"
               old-json-edges "edges"
               :as old-json} (slurp-json "old-catalog.json" debug-dir)
              {new-json-res "resources"
               new-json-edges "edges"
               :as new-json} (slurp-json "new-catalog.json" debug-dir)
              catalog-metadata (slurp-json "catalog-metadata.json" debug-dir)]

          (is (some #(= "/etc/foobar/bazv2" (:title %)) new-edn-res))
          (is (some #(= "/etc/foobar/bazv2" (get % "title")) new-json-res))
          (is (not-any? #(= "/etc/foobar/bazv2" (get % "title")) old-json-res))
          (is (not-any? #(= "/etc/foobar/bazv2" (:title %)) old-edn-res))

          (is (seq old-edn-res))
          (is (seq old-edn-edges))
          (is (seq old-json-res))
          (is (seq old-json-edges))

          (are [metadata-key] (contains? catalog-metadata metadata-key)
               "java version"
               "new catalog hash"
               "old catalog hash"
               "database name"
               "database version")

          (are [metadata-key] (and (utils/string-contains? (:certname new-catalog)
                                                           (get catalog-metadata metadata-key))
                                   (.startsWith (get catalog-metadata metadata-key) debug-dir))
               "old catalog path - edn"
               "new catalog path - edn"
               "old catalog path - json"
               "new catalog path - json")

          (is (not= (get catalog-metadata "new catalog hash")
                    (get catalog-metadata "old catalog hash"))))))))

(deftest debug-catalog-output-filename-uniqueness
  (fixt/with-test-db
    (fn []
      (let [debug-dir (fs/absolute-path (tu/temp-dir))
            {:keys [persisted-hash] :as orig-catalog} (persist-catalog (:basic catalogs))

            new-catalog-1 (assoc-in (:basic catalogs)
                                    [:resources {:type "File" :title "/etc/foobar/bazv2"}]
                                    {:type       "File"
                                     :title      "/etc/foobar/bazv2"})
            new-hash-1 (catalog-similarity-hash new-catalog-1)

            new-catalog-2 (assoc-in (:basic catalogs)
                                    [:resources {:type "File" :title "/etc/foobar/bazv3"}]
                                    {:type       "File"
                                     :title      "/etc/foobar/bazv2"})
            new-hash-2 (catalog-similarity-hash new-catalog-2)]

        (is (nil? (fs/list-dir debug-dir)))
        (debug-catalog debug-dir new-hash-1 new-catalog-1)
        (debug-catalog debug-dir new-hash-2 new-catalog-2)
        (is (= 10 (count (fs/list-dir debug-dir))))))))

(deftest comparing-resources
  (let [unsorted-results [{:title "z" :type "z"}
                          {:title "z" :type "w"}
                          {:title "z" :type "x"}
                          {:title "b" :type "z"}
                          {:title "a" :type "z"}
                          {:title "c" :type "c"}
                          {:title "c" :type "c"}]
        sorted-results [{:title "a" :type "z"}
                        {:title "b" :type "z"}
                        {:title "c" :type "c"}
                        {:title "c" :type "c"}
                        {:title "z" :type "w"}
                        {:title "z" :type "x"}
                        {:title "z" :type "z"}]]

    (is (= sorted-results
           (sort resource-comparator unsorted-results)))
    (is (= sorted-results
           (sort resource-comparator (shuffle unsorted-results))))
    (is (= sorted-results
           (sort resource-comparator
                 (sort resource-comparator unsorted-results))))))

(deftest comparing-edges
  (let [unsorted-results [{:source {:title "a" :type "z"}
                           :target {:title "b" :type "z"}
                           :relationship "contains"}
                          {:source {:title "a" :type "c"}
                           :target {:title "b" :type "c"}
                           :relationship "contains"}
                          {:source {:title "a" :type "c"}
                           :target {:title "b" :type "b"}
                           :relationship "contains"}
                          {:source {:title "a" :type "z"}
                           :target {:title "b" :type "z"}
                           :relationship "before"}
                          {:source {:title "a" :type "b"}
                           :target {:title "b" :type "z"}
                           :relationship "contains"}]
        sorted-results [{:source {:title "a" :type "b"}
                         :target {:title "b" :type "z"}
                         :relationship "contains"}
                        {:source {:title "a" :type "c"}
                         :target {:title "b" :type "b"}
                         :relationship "contains"}
                        {:source {:title "a" :type "c"}
                         :target {:title "b" :type "c"}
                         :relationship "contains"}
                        {:source {:title "a" :type "z"}
                         :target {:title "b" :type "z"}
                         :relationship "before"}
                        {:source {:title "a" :type "z"}
                         :target {:title "b" :type "z"}
                         :relationship "contains"}]]

    (is (= sorted-results
           (sort edge-comparator unsorted-results)))
    (is (= sorted-results
           (sort edge-comparator (shuffle unsorted-results))))
    (is (= sorted-results
           (sort edge-comparator
                 (sort edge-comparator unsorted-results))))))
