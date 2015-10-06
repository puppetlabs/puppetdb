(ns puppetlabs.puppetdb.catalogs-test
  (:import [clojure.lang ExceptionInfo])
  (:require [schema.core :as s]
            [puppetlabs.puppetdb.catalogs :refer :all]
            [puppetlabs.puppetdb.examples :refer :all]
            [clojure.test :refer :all]
            [clojure.set :as set]
            [puppetlabs.puppetdb.utils :as utils]
            [clj-time.core :refer [now]]))

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
      (let [catalog (dissoc (:basic catalogs) :api_version)
            v6-catalog (dissoc catalog :code_id)]
        (testing "should accept catalogs with the correct set of keys"
          (= catalog (s/validate catalog-wireformat-schema catalog))
          (= v6-catalog (s/validate catalog-v6-wireformat-schema v6-catalog)))

        (testing "should fail if the catalog has an extra key"
          (is (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                              (s/validate catalog-v6-wireformat-schema (assoc v6-catalog :classes #{}))))
          (is (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                              (s/validate catalog-wireformat-schema (assoc catalog :classes #{})))))

        (testing "should fail if the catalog is missing a key"
          (is (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                              (s/validate catalog-wireformat-schema (dissoc catalog :resources))))
          (is (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                              (s/validate catalog-v6-wireformat-schema (dissoc v6-catalog :resources)))))))))

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

(deftest test-v7-conversion
  (testing "v6->v7"
    (let [v6-catalog (get-in wire-catalogs [6 :empty])]
      (are [pred key] (pred (contains? v6-catalog key))
        false? :code_id)

      (let [v7-catalog (parse-catalog v6-catalog 6 (now))]
        (are [pred key] (pred (contains? v7-catalog key))
          true? :code_id))))

  (testing "v5->v7"
    (let [v5-catalog (get-in wire-catalogs [5 :empty])]
      (are [pred key] (pred (contains? v5-catalog key))
           true? :name
           false? :certname
           false? :code_id
           true? :transaction-uuid)

      (let [v7-catalog (parse-catalog v5-catalog 5 (now))]
        (are [pred key] (pred (contains? v7-catalog key))
             false? :name
             true? :code_id
             true? :certname
             false? :transaction-uuid
             true? :transaction_uuid))))

  (testing "v4->v7"
    (let [v4-catalog (get-in wire-catalogs [4 :empty])]
      (are [pred key] (pred (contains? v4-catalog key))
           true? :name
           false? :certname
           false? :code_id
           true? :transaction-uuid
           false? :producer_timestamp
           false? :producer-timestamp)

      (let [v7-catalog (parse-catalog v4-catalog 4 (now))]
        (are [pred key] (pred (contains? v7-catalog key))
             false? :name
             true? :certname
             false? :transaction-uuid
             true? :code_id
             true? :transaction_uuid
             true? :producer_timestamp
             false? :producer-timestamp))))

  (testing "v5 with dashed resource-param names"
    (let [v5-catalog (-> wire-catalogs
                         (get-in [5 :empty])
                         (update :resources (fn [resources]
                                              (mapv #(assoc-in % [:parameters :foo-bar] "baz") resources))))]
      (is (true? (contains? (get-in v5-catalog [:resources 0 :parameters]) :foo-bar)))
      (let [v6-catalog (parse-catalog v5-catalog 5 (now))]
        (is (every? (comp true? #(contains? % :foo-bar) :parameters) (vals (:resources v6-catalog))))
        (is (every? (comp false? #(contains? % :foo_bar) :parameters) (vals (:resources v6-catalog))))))))
