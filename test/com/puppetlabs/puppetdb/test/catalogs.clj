(ns com.puppetlabs.puppetdb.test.catalogs
  (:import [clojure.lang ExceptionInfo])
  (:require [schema.core :as s])
  (:use [com.puppetlabs.puppetdb.catalogs]
        [com.puppetlabs.puppetdb.catalog.utils]
        [com.puppetlabs.puppetdb.examples]
        [clojure.test]))

(defn catalog-before-and-after
  "Test that a wire format catalog is equal, post-processing, to the
  indicated puppetdb representation"
  [version before after]
  (let [b (parse-catalog before version)
        a after]
    ; To make it easier to pinpoint the source of errors, we test
    ; individual components of the catalog first, then finally test
    ; equality of the entire catalog
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

(deftest catalog-restructuring
  (testing "Transforming catalog metadata"
    (let [transform-fn (comp #(s/validate (catalog-schema :v1) %) collapse)]
      (testing "should work on well-formed input"
        (let [catalog  {:data {:name "myhost" :version "12345" :foo "bar" :transaction-uuid "HIYA" :resources [{}] :edges [{}]}
                        :metadata {:api_version 1}}]
          (is (= (transform-fn (assoc-in catalog [:data :transaction-uuid] "HIYA"))
                 {:name "myhost" :version "12345" :api_version 1 :foo "bar" :transaction-uuid "HIYA" :resources [{}] :edges [{}]}))))

      (testing "should error on malformed input"
        (is (thrown? AssertionError (transform-fn {})))
        (is (thrown? AssertionError (transform-fn nil)))
        (is (thrown? AssertionError (transform-fn [])))

        (testing "like non-numeric api versions"
          (let [catalog  {:data {:name "myhost" :version "12345"}
                          :metadata {:api_version "123"}}]
            (is (thrown? ExceptionInfo (transform-fn catalog)))))

        (testing "like a missing 'data' section"
          (let [catalog  {:name "myhost" :version "12345"
                          :metadata {:api_version 123}}]
            (is (thrown? AssertionError (transform-fn catalog)))))))))

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
            v4-catalog (dissoc catalog :api_version :producer-timestamp)
            v3-catalog (dissoc catalog :environment :producer-timestamp)
            v2-catalog (dissoc catalog :transaction-uuid :environment :producer-timestamp)
            v1-catalog (assoc catalog :something "random")]
        (testing "should accept catalogs with the correct set of keys"
          (are [version catalog] (= catalog (s/validate (catalog-schema version) catalog))
            :all catalog
            :v5 v5-catalog
            :v4 v4-catalog
            :v3 v3-catalog
            :v2 v2-catalog
            :v1 v1-catalog))

        (testing "should fail if the catalog has an extra key"
          (are [version catalog] (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                                                   (s/validate (catalog-schema version) (assoc catalog :classes #{})))
            :all catalog
            :v5 v5-catalog
            :v4 v4-catalog
            :v3 v3-catalog
            :v2 v2-catalog
               ;; :v1 allows extra keys
))

        (testing "should fail if the catalog is missing a key"
          (are [version catalog] (thrown-with-msg? ExceptionInfo #"Value does not match schema"
                                                   (s/validate (catalog-schema version) (dissoc catalog :version)))

            :all catalog
            :v5 v5-catalog
            :v4 v4-catalog
            :v3 v3-catalog
            :v2 v2-catalog
            :v1 v1-catalog))))))

(deftest resource-normalization
  (let [; Synthesize some fake resources
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
      ; nil resources aren't allowed
      (is (thrown? AssertionError (transform-resources {:resources nil})))
      ; missing resources aren't allowed
      (is (thrown? AssertionError (transform-resources {})))
      ; pre-created resource maps aren't allow
      (is (thrown? AssertionError (transform-resources {:resources {}}))))))

(deftest complete-transformation-v1
  (catalog-before-and-after 1
                            {:data
                             {:edges [{:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Stage"},
                                       :target {:title "Settings", :type "Class"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/baz", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/bar", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Stage"},
                                       :target {:title "main", :type "Class"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/quux", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/bar", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/baz", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"}}],
                              :name "nick-lewis.puppetlabs.lan",
                              :resources [{:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 3,
                                           :parameters {:require ["File[/tmp/bar]" "File[/tmp/baz]"]},
                                           :tags ["file" "class"],
                                           :title "/tmp/foo",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {},
                                           :tags ["class" "settings"],
                                           :title "Settings",
                                           :type "Class"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 11,
                                           :parameters {:require "File[/tmp/quux]"},
                                           :tags ["file" "class"],
                                           :title "/tmp/baz",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {:name "main"},
                                           :tags ["stage"],
                                           :title "main",
                                           :type "Stage"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 7,
                                           :parameters {:require ["File[/tmp/baz]" "File[/tmp/quux]"]},
                                           :tags ["file" "class"],
                                           :title "/tmp/bar",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {:name "main"},
                                           :tags ["class"],
                                           :title "main",
                                           :type "Class"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 12,
                                           :parameters {},
                                           :tags ["file" "class"],
                                           :title "/tmp/quux",
                                           :type "File"}],
                              :classes [],
                              :tags [],
                              :version "1330995750"},
                             :document_type "Catalog",
                             :metadata {:api_version 1}}

                            {:name "nick-lewis.puppetlabs.lan",
                             :api_version 1,
                             :edges #{{:source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/bar", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Stage"},
                                       :target {:title "Settings", :type "Class"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/baz", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/baz", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/quux", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Stage"},
                                       :target {:title "main", :type "Class"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :required-by}},
                             :resources {{:type "File", :title "/tmp/foo"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 3,
                                          :parameters {:require ["File[/tmp/bar]" "File[/tmp/baz]"]},
                                          :tags #{"class" "file"},
                                          :title "/tmp/foo",
                                          :type "File"},
                                         {:type "Class", :title "Settings"}
                                         {:exported false,
                                          :parameters {},
                                          :tags #{"settings" "class"},
                                          :title "Settings",
                                          :type "Class"},
                                         {:type "File", :title "/tmp/baz"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 11,
                                          :parameters {:require "File[/tmp/quux]"},
                                          :tags #{"class" "file"},
                                          :title "/tmp/baz",
                                          :type "File"},
                                         {:type "Stage", :title "main"}
                                         {:exported false,
                                          :parameters {:name "main"},
                                          :tags #{"stage"},
                                          :title "main",
                                          :type "Stage"},
                                         {:type "File", :title "/tmp/bar"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 7,
                                          :parameters {:require ["File[/tmp/baz]" "File[/tmp/quux]"]},
                                          :tags #{"class" "file"},
                                          :title "/tmp/bar",
                                          :type "File"},
                                         {:type "Class", :title "main"}
                                         {:exported false,
                                          :parameters {:name "main"},
                                          :tags #{"class"},
                                          :title "main",
                                          :type "Class"},
                                         {:type "File", :title "/tmp/quux"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 12,
                                          :parameters {},
                                          :tags #{"class" "file"},
                                          :title "/tmp/quux",
                                          :type "File"}},
                             :version "1330995750",
                             :transaction-uuid nil
                             :producer-timestamp nil
                             :environment nil}))

(deftest complete-transformation-v2
  (catalog-before-and-after 2
                            {:data
                             {:edges [{:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Stage"},
                                       :target {:title "Settings", :type "Class"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/baz", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/bar", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Stage"},
                                       :target {:title "main", :type "Class"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/quux", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/bar", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/baz", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"}}],
                              :name "nick-lewis.puppetlabs.lan",
                              :resources [{:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 3,
                                           :parameters {:require ["File[/tmp/bar]" "File[/tmp/baz]"]},
                                           :tags ["file" "class"],
                                           :title "/tmp/foo",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {},
                                           :tags ["class" "settings"],
                                           :title "Settings",
                                           :type "Class"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 11,
                                           :parameters {:require "File[/tmp/quux]"},
                                           :tags ["file" "class"],
                                           :title "/tmp/baz",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {:name "main"},
                                           :tags ["stage"],
                                           :title "main",
                                           :type "Stage"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 7,
                                           :parameters {:require ["File[/tmp/baz]" "File[/tmp/quux]"]},
                                           :tags ["file" "class"],
                                           :title "/tmp/bar",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {:name "main"},
                                           :tags ["class"],
                                           :title "main",
                                           :type "Class"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 12,
                                           :parameters {},
                                           :tags ["file" "class"],
                                           :title "/tmp/quux",
                                           :type "File"}],
                              :version "1330995750"},
                             :document_type "Catalog",
                             :metadata {:api_version 1}}

                            {:name "nick-lewis.puppetlabs.lan",
                             :api_version 1,
                             :edges #{{:source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/bar", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Stage"},
                                       :target {:title "Settings", :type "Class"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/baz", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/baz", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/quux", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Stage"},
                                       :target {:title "main", :type "Class"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :required-by}},
                             :resources {{:type "File", :title "/tmp/foo"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 3,
                                          :parameters {:require ["File[/tmp/bar]" "File[/tmp/baz]"]},
                                          :tags #{"class" "file"},
                                          :title "/tmp/foo",
                                          :type "File"},
                                         {:type "Class", :title "Settings"}
                                         {:exported false,
                                          :parameters {},
                                          :tags #{"settings" "class"},
                                          :title "Settings",
                                          :type "Class"},
                                         {:type "File", :title "/tmp/baz"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 11,
                                          :parameters {:require "File[/tmp/quux]"},
                                          :tags #{"class" "file"},
                                          :title "/tmp/baz",
                                          :type "File"},
                                         {:type "Stage", :title "main"}
                                         {:exported false,
                                          :parameters {:name "main"},
                                          :tags #{"stage"},
                                          :title "main",
                                          :type "Stage"},
                                         {:type "File", :title "/tmp/bar"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 7,
                                          :parameters {:require ["File[/tmp/baz]" "File[/tmp/quux]"]},
                                          :tags #{"class" "file"},
                                          :title "/tmp/bar",
                                          :type "File"},
                                         {:type "Class", :title "main"}
                                         {:exported false,
                                          :parameters {:name "main"},
                                          :tags #{"class"},
                                          :title "main",
                                          :type "Class"},
                                         {:type "File", :title "/tmp/quux"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 12,
                                          :parameters {},
                                          :tags #{"class" "file"},
                                          :title "/tmp/quux",
                                          :type "File"}},
                             :version "1330995750",
                             :transaction-uuid nil
                             :producer-timestamp nil
                             :environment nil}))

(deftest complete-transformation-v3
  (catalog-before-and-after 3
                            {:data
                             {:edges [{:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Stage"},
                                       :target {:title "Settings", :type "Class"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/baz", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/bar", :type "File"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Stage"},
                                       :target {:title "main", :type "Class"}}
                                      {:relationship "contains",
                                       :source {:title "main", :type "Class"},
                                       :target {:title "/tmp/quux", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/bar", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/baz", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"}}
                                      {:relationship "required-by",
                                       :source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"}}],
                              :name "nick-lewis.puppetlabs.lan",
                              :resources [{:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 3,
                                           :parameters {:require ["File[/tmp/bar]" "File[/tmp/baz]"]},
                                           :tags ["file" "class"],
                                           :title "/tmp/foo",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {},
                                           :tags ["class" "settings"],
                                           :title "Settings",
                                           :type "Class"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 11,
                                           :parameters {:require "File[/tmp/quux]"},
                                           :tags ["file" "class"],
                                           :title "/tmp/baz",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {:name "main"},
                                           :tags ["stage"],
                                           :title "main",
                                           :type "Stage"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 7,
                                           :parameters {:require ["File[/tmp/baz]" "File[/tmp/quux]"]},
                                           :tags ["file" "class"],
                                           :title "/tmp/bar",
                                           :type "File"}
                                          {:exported false,
                                           :parameters {:name "main"},
                                           :tags ["class"],
                                           :title "main",
                                           :type "Class"}
                                          {:exported false,
                                           :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                           :line 12,
                                           :parameters {},
                                           :tags ["file" "class"],
                                           :title "/tmp/quux",
                                           :type "File"}],
                              :version "1330995750"
                              :transaction-uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"},
                             :document_type "Catalog",
                             :metadata {:api_version 1}}

                            {:name "nick-lewis.puppetlabs.lan",
                             :api_version 1,
                             :edges #{{:source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/bar", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/bar", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Stage"},
                                       :target {:title "Settings", :type "Class"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/quux", :type "File"},
                                       :target {:title "/tmp/baz", :type "File"},
                                       :relationship :required-by}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/baz", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Class"},
                                       :target {:title "/tmp/quux", :type "File"},
                                       :relationship :contains}
                                      {:source {:title "main", :type "Stage"},
                                       :target {:title "main", :type "Class"},
                                       :relationship :contains}
                                      {:source {:title "/tmp/baz", :type "File"},
                                       :target {:title "/tmp/foo", :type "File"},
                                       :relationship :required-by}},
                             :resources {{:type "File", :title "/tmp/foo"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 3,
                                          :parameters {:require ["File[/tmp/bar]" "File[/tmp/baz]"]},
                                          :tags #{"class" "file"},
                                          :title "/tmp/foo",
                                          :type "File"},
                                         {:type "Class", :title "Settings"}
                                         {:exported false,
                                          :parameters {},
                                          :tags #{"settings" "class"},
                                          :title "Settings",
                                          :type "Class"},
                                         {:type "File", :title "/tmp/baz"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 11,
                                          :parameters {:require "File[/tmp/quux]"},
                                          :tags #{"class" "file"},
                                          :title "/tmp/baz",
                                          :type "File"},
                                         {:type "Stage", :title "main"}
                                         {:exported false,
                                          :parameters {:name "main"},
                                          :tags #{"stage"},
                                          :title "main",
                                          :type "Stage"},
                                         {:type "File", :title "/tmp/bar"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 7,
                                          :parameters {:require ["File[/tmp/baz]" "File[/tmp/quux]"]},
                                          :tags #{"class" "file"},
                                          :title "/tmp/bar",
                                          :type "File"},
                                         {:type "Class", :title "main"}
                                         {:exported false,
                                          :parameters {:name "main"},
                                          :tags #{"class"},
                                          :title "main",
                                          :type "Class"},
                                         {:type "File", :title "/tmp/quux"}
                                         {:exported false,
                                          :file "/Users/nicklewis/projects/puppetdb/test.pp",
                                          :line 12,
                                          :parameters {},
                                          :tags #{"class" "file"},
                                          :title "/tmp/quux",
                                          :type "File"}},
                             :version "1330995750",
                             :transaction-uuid "68b08e2a-eeb1-4322-b241-bfdf151d294b"
                             :producer-timestamp nil
                             :environment nil}))

(deftest test-canonical-catalog
  (let [catalog (:basic catalogs)]
    (testing "conversion to :all should never lose information"
      (doseq [version [:v1 :v2 :v3 :v4 :v5]]
        (is (= (canonical-catalog version catalog)
               (canonical-catalog version (canonical-catalog :all catalog))))))
    (testing "version 1"
      (let [v1-catalog (canonical-catalog :v1 (assoc catalog :more "stuff"))
            v1->all-catalog (canonical-catalog :all (canonical-catalog :v1 (assoc catalog :more "stuff")))]
        (is (string? (:transaction-uuid catalog)))
        (is (not (contains? v1-catalog :transaction-uuid)))
        (is (not (contains? v1-catalog :environment)))
        (is (= "stuff" (:more v1-catalog)))
        (is (= (dissoc v1->all-catalog :transaction-uuid :environment :producer-timestamp)
               (dissoc v1-catalog :more)))))
    (testing "version 2"
      (let [v2-catalog (canonical-catalog :v2 catalog)]
        (is (string? (:transaction-uuid catalog)))
        (is (not (contains? v1-catalog :transaction-uuid)))
        (is (not (contains? v1-catalog :environment)))))
    (testing "version 3"
      (let [v3-catalog (canonical-catalog :v3 catalog)]
        (is (= (:transaction-uuid catalog)
               (:transaction-uuid v3-catalog)))
        (is (not (contains? v1-catalog :environment)))))
    (testing "version 4"
      (let [v4-catalog (canonical-catalog :v4 catalog)]
        (is (= (:transaction-uuid catalog)
               (:transaction-uuid v4-catalog)))
        (is (= (:environment catalog)
               (:environment v4-catalog)))
        (is (not (contains? v4-catalog :api_version)))))
    (testing "version 5"
      (let [v5-catalog (canonical-catalog :v5 catalog)]
        (is (= (:transaction-uuid catalog)
               (:transaction-uuid v5-catalog)))
        (is (= (:environment catalog)
               (:environment v5-catalog)))
        (is (= (:producer-timestamp catalog)
               (:producer-timestamp v5-catalog)))
        (is (not (contains? v5-catalog :api_version)))))))

(deftest test-canonical->wire-format
  (let [catalog (:basic catalogs)]
    (testing "version 1"
      (let [{:keys [metadata data] :as wire-catalog} (canonical->wire-format :v1 (assoc catalog :more "stuff"))]
        (is (= {:api_version 1}
               metadata))
        (is (= (dissoc catalog :api_version :transaction-uuid :environment :producer-timestamp :more)
               data))))
    (testing "version 2"
      (let [{:keys [metadata data] :as wire-catalog} (canonical->wire-format :v2 catalog)]
        (is (= {:api_version 1}
               metadata))
        (is (= (dissoc catalog :api_version :transaction-uuid :environment :producer-timestamp)
               data))))
    (testing "version 3"
      (let [{:keys [metadata data] :as wire-catalog} (canonical->wire-format :v3 catalog)]
        (is (= {:api_version 1}
               metadata))
        (is (= (dissoc catalog :api_version :environment :producer-timestamp)
               data))))
    (testing "version 4"
      (let [wire-catalog (canonical->wire-format :v4 catalog)]
        (is (not (contains? wire-catalog :data)))
        (is (not (contains? wire-catalog :metadata)))
        (is (= (dissoc catalog :api_version :producer-timestamp)
               wire-catalog))))
    (testing "version 5"
      (let [wire-catalog (canonical->wire-format :v5 catalog)]
        (is (not (contains? wire-catalog :data)))
        (is (not (contains? wire-catalog :metadata)))
        (is (= (dissoc catalog :api_version)
               wire-catalog))))))
