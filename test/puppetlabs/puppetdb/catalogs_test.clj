(ns puppetlabs.puppetdb.catalogs-test
  (:import [clojure.lang ExceptionInfo])
  (:require [schema.core :as s]
            [puppetlabs.puppetdb.catalogs :refer :all]
            [puppetlabs.puppetdb.testutils.catalogs :refer [canonical->wire-format]]
            [puppetlabs.puppetdb.examples :refer :all]
            [clojure.test :refer :all]))

(defn catalog-before-and-after
  "Test that a wire format catalog is equal, post-processing, to the
  indicated puppetdb representation"
  [version before after]
  (let [b (parse-catalog before version)
        a after]
    ;; To make it easier to pinpoint the source of errors, we test
    ;; individual components of the catalog first, then finally test
    ;; equality of the entire catalog
    (is (= (:name b) (:name a)))
    (is (= (:version b) (:version a)))
    (is (= (:api_version b) (:api_version a)))
    (is (= (:edges b) (:edges a)))
    (is (= (:resources b) (:resources a)))
    (is (= b a))))

(deftest parsing-resource-string
  (testing "Resource string parsing"
    (testing "should error on bad input"
      (is (thrown? AssertionError (resource-spec-to-map "Class[Foo")))
      (is (thrown? AssertionError (resource-spec-to-map "ClassFoo]")))
      (is (thrown? AssertionError (resource-spec-to-map "ClassFoo")))
      (is (thrown? AssertionError (resource-spec-to-map nil))))

    (testing "should correctly parse well-formed input"
      (is (= (resource-spec-to-map "Class[Foo]")
             {:type "Class" :title "Foo"}))
      (is (= (resource-spec-to-map "Class[F[oo]]")
             {:type "Class" :title "F[oo]"}))
      (is (= (resource-spec-to-map "Notify[Foo\nbar]")
             {:type "Notify" :title "Foo\nbar"})))))

(deftest integrity-checking
  (testing "Catalog validation"
    (testing "should return the catalog unchanged"
      (let [catalog (:basic catalogs)]
        (is (= catalog (validate catalog)))))

    (testing "resource validation"
      (testing "should fail when a resource has non-lower-case tags"
        (let [resources {{:type "Type" :title "foo"} {:tags ["foo" "BAR"]}}
              catalog {:resources resources}]
          (is (thrown-with-msg? IllegalArgumentException #"invalid tag 'BAR'"
                                (validate-resources catalog)))))

      (testing "should fail when a resource has tags with bad characters"
        (let [resources {{:type "Type" :title "foo"} {:tags ["foo" "b@r"]}}
              catalog {:resources resources}]
          (is (thrown-with-msg? IllegalArgumentException #"invalid tag 'b@r'"
                                (validate-resources catalog)))))

      (testing "should not fail when a resource has only lower-case tags"
        (let [resources {{:type "Type" :title "foo"} {:tags ["foo" "bar"]}}
              catalog {:resources resources}]
          (is (= catalog (validate-resources catalog))))))

    (testing "edge validation"
      (let [source {:type "Type" :title "source"}
            target {:type "Type" :title "target"}]
        (testing "should fail when edges mention missing resources"
          (is (thrown? IllegalArgumentException
                       (validate-edges {:edges #{{:source source :target target :relationship :before}}
                                        :resources {}}))))

        (testing "should fail when edges have an invalid relationship"
          (is (thrown? IllegalArgumentException
                       (validate-edges {:edges #{{:source source :target target :relationship :madly-in-love-with}}
                                        :resources {source source
                                                    target target}}))))

        (testing "should accept all valid relationship types"
          (let [edges (set (for [rel #{:contains :required-by :notifies :before :subscription-of}]
                             {:source source :target target :relationship rel}))
                catalog {:edges edges
                         :resources {source source
                                     target target}}]
            (is (= catalog (validate-edges catalog)))))))

    (testing "key validation"
      (let [catalog (:basic catalogs)
            v5-catalog (dissoc catalog :api_version)
            v4-catalog (dissoc catalog :api_version :producer_timestamp)
            v3-catalog (dissoc catalog :environment :producer_timestamp)
            v2-catalog (dissoc catalog :transaction_uuid :environment :producer_timestamp)
            v1-catalog (assoc catalog :something "random")]
        (testing "should accept catalogs with the correct set of keys"
          (are [version catalog] (= catalog (s/validate (catalog-wireformat version) catalog))
               :all catalog
               :v5 v5-catalog
               ))

        (testing "should fail if the catalog has an extra key"
          (are [version catalog] (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                                                   (s/validate (catalog-wireformat version) (assoc catalog :classes #{})))
               :all catalog
               :v5 v5-catalog
               ))

        (testing "should fail if the catalog is missing a key"
          (are [version catalog] (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                                                   (s/validate (catalog-wireformat version) (dissoc catalog :version)))

               :all catalog
               :v5 v5-catalog
               ))))))

(deftest resource-normalization
  (let [;; Synthesize some fake resources
        catalog {:resources [{:type       "File"
                              :title      "/etc/foobar"
                              :exported   false
                              :line       1234
                              :file       "/tmp/foobar.pp"
                              :tags       ["class" "foobar"]
                              :parameters {:ensure "present"
                                           :user   "root"
                                           :group  "root"
                                           :source "puppet:///foobar/foo/bar"}}]}]
    (is (= (-> catalog
               (transform-resources))
           {:resources {{:type "File" :title "/etc/foobar"} {:type       "File"
                                                             :title      "/etc/foobar"
                                                             :exported   false
                                                             :line       1234
                                                             :file       "/tmp/foobar.pp"
                                                             :tags       #{"class" "foobar"}
                                                             :parameters {:ensure "present"
                                                                          :user   "root"
                                                                          :group  "root"
                                                                          :source "puppet:///foobar/foo/bar"}}}}))

    (let [resources (:resources catalog)
          new-resources (conj resources (first resources))
          catalog (assoc catalog :resources new-resources)]
      (testing "Duplicate resources should throw error"
        (is (thrown? AssertionError
                     (transform-resources catalog)))))

    (testing "Resource normalization edge case handling"
      ;; nil resources aren't allowed
      (is (thrown? AssertionError (transform-resources {:resources nil})))
      ;; missing resources aren't allowed
      (is (thrown? AssertionError (transform-resources {})))
      ;; pre-created resource maps aren't allow
      (is (thrown? AssertionError (transform-resources {:resources {}}))))))

(deftest test-canonical-catalog
  (let [catalog (:basic catalogs)]
    (testing "conversion to :all should never lose information"
      (doseq [version [:v5]]
        (is (= (canonical-catalog version catalog)
               (canonical-catalog version (canonical-catalog :all catalog))))))
    (testing "version 5"
      (let [v5-catalog (canonical-catalog :v5 catalog)]
        (is (= (:transaction_uuid catalog)
               (:transaction_uuid v5-catalog)))
        (is (= (:environment catalog)
               (:environment v5-catalog)))
        (is (= (:producer_timestamp catalog)
               (:producer_timestamp v5-catalog)))
        (is (not (contains? v5-catalog :api_version)))))))

(deftest test-canonical->wire-format
  (let [catalog (:basic catalogs)]
    (testing "version 5"
      (let [wire-catalog (canonical->wire-format :v5 catalog)]
        (is (not (contains? wire-catalog :data)))
        (is (not (contains? wire-catalog :metadata)))
        (is (= (dissoc catalog :api_version)
               wire-catalog))))))
