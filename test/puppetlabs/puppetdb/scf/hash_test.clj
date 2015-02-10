(ns puppetlabs.puppetdb.scf.hash-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.hash :refer :all]
            [puppetlabs.puppetdb.random :as random]
            [clojure.math.combinatorics :refer (combinations subsets)]
            [puppetlabs.puppetdb.examples :refer [catalogs]]
            [puppetlabs.puppetdb.catalog.utils :as catutils]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.report.utils :as reputils]))

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
      (let [input {:b "asdf" :a {:z "asdf" :k [:z {:z [:a {:a [1 2] :b 2}] :a 1} :c] :a {:m "asdf" :b "asdf"}}}]
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
    (let [sample {:name  "foobar.baz"
                  :resources {:foo {:type "Type" :title "foo" :parameters {:a 1 :c 3 :b {:z 26 :c 3}} :file "/tmp" :line 3 :tags ["foo" "bar"]}}
                  :edges     [{:source {:type "Type" :title "foo"} :target {:type "File" :title "/tmp"}}]}]

      (testing "shouldn't change for identical input"
        (is (= (catalog-similarity-hash sample)
               (catalog-similarity-hash sample))))

      (testing "should return the same predictable string"
        (is (= (catalog-similarity-hash sample)
               "40f42c42bcd81ae28ab306ab64498f0bd6674ce6")))))

  (testing "resource-event-identity-string"
    (let [sample {:resource_type  "Type"
                  :resource_title "foo"
                  :property       "name"
                  :timestamp      "foo"
                  :status         "skipped"
                  :old_value      "baz"
                  :new_value      "foo"
                  :message        "Name changed from baz to foo"}]

      (testing "shouldn't change for identical input"
        (is (= (resource-event-identity-string sample)
               (resource-event-identity-string sample))))

      (testing "should return the same predictable string"
        (is (= (resource-event-identity-string sample)
               "{\"file\":null,\"line\":null,\"message\":\"Name changed from baz to foo\",\"new_value\":\"foo\",\"old_value\":\"baz\",\"property\":\"name\",\"resource_title\":\"foo\",\"resource_type\":\"Type\",\"status\":\"skipped\",\"timestamp\":\"foo\"}")))))

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
                  :puppet_version "3.2.1"
                  :report_format 1
                  :configuration_version "asdffdsa"
                  :start_time "2012-03-01-12:31:11.123"
                  :end_time   "2012-03-01-12:31:31.123"
                  :resource_events [
                                    {:type "Type"
                                     :title "title"
                                     :parameters {:d {:b 2 :c [:a :b :c]} :c 3 :a 1}
                                     :exported false :file "/tmp/zzz"
                                     :line 15}]}]

      (testing "should return sorted predictable string output"
        (is (= (report-identity-hash sample)
               "7fddeb9eb1f4469acb9ea6c5d1bea15f8654326b")))

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
          tweak-api-version  #(update-in % [:api_version] inc)
          tweak-version      #(update-in % [:version] str)
          ;; List of all the tweaking functions
          chaos-monkeys      [tweak-api-version tweak-version]
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
                            #(assoc % :puppet_version (str (:puppet_version %) "foo"))
                            #(assoc % :report_format (inc (:report_format %)))
                            #(assoc % :configuration_version (str (:configuration_version %) "foo"))
                            #(assoc % :start_time (str (:start_time %) "foo"))
                            #(assoc % :end_time (str (:start_time %) "foo"))]]
        (doseq [mod-report-fn mod-report-fns]
          (is (not= report-hash (report-identity-hash (mod-report-fn report)))))))))

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
