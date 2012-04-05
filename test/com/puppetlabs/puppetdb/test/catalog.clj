(ns com.puppetlabs.puppetdb.test.catalog
  (:use [com.puppetlabs.puppetdb.catalog]
        [com.puppetlabs.puppetdb.catalog.utils]
        [clojure.test]))

(defn catalog-before-and-after
  "Test that a wire format catalog is equal, post-processing, to the
  indicated puppetdb representation"
  [before after]
  (let [b (parse-from-json-obj before)
        a after]
    ; To make it easier to pinpoint the source of errors, we test
    ; individual components of the catalog first, then finally test
    ; equality of the entire catalo0g
    (is (= (:certname b) (:certname a)))
    (is (= (:version b) (:version a)))
    (is (= (:api-version b) (:api-version a)))
    (is (= (:tags b) (:tags a)))
    (is (= (:classes b) (:classes a)))
    (is (= (:edges b) (:edges a)))
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
  (testing "Restructuring catalogs"
    (testing "should work on well-formed input"
      (is (= (restructure-catalog {:data {:name "myhost" :version "12345" :foo "bar"}
                                   :metadata {:api_version 1}})
             {:certname "myhost" :version "12345" :api-version 1 :foo "bar" :puppetdb-version CATALOG-VERSION})))

    (testing "should error on malformed input"
      (is (thrown? AssertionError (restructure-catalog {})))
      (is (thrown? AssertionError (restructure-catalog nil)))
      (is (thrown? AssertionError (restructure-catalog [])))

      (testing "like non-numeric api versions"
        (is (thrown? AssertionError (restructure-catalog {:data {:name "myhost" :version "12345"}
                                                          :metadata {:api_version "123"}}))))

      (testing "like a missing 'data' key"
        (is (thrown? AssertionError (restructure-catalog {:name "myhost" :version "12345"
                                                          :metadata {:api_version 123}})))))))


(deftest integrity-checking
  (testing "Catalog integrity checking"
    (testing "should fail when edges mention missing resources"
      (is (thrown? IllegalArgumentException
                   (check-edge-integrity {:edges #{{:source "a" :target "b" :relationship :before}}
                                          :resources {}}))))))

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

    (testing "Resource tags to sets"
         (is (= (-> catalog
                    (setify-resource-tags))
                {:resources [{:type       "File"
                              :title      "/etc/foobar"
                              :exported   false
                              :line       1234
                              :file       "/tmp/foobar.pp"
                              :tags       #{"class" "foobar"}
                              :parameters {:ensure "present"
                                           :user   "root"
                                           :group  "root"
                                           :source "puppet:///foobar/foo/bar"}}]})))

    (testing "Resource key extraction"
         (is (= (-> catalog
                    (mapify-resources))
                {:resources {{:type "File" :title "/etc/foobar"} {:type       "File"
                                                                  :title      "/etc/foobar"
                                                                  :exported   false
                                                                  :line       1234
                                                                  :file       "/tmp/foobar.pp"
                                                                  :tags       ["class" "foobar"]
                                                                  :parameters {:ensure "present"
                                                                               :user   "root"
                                                                               :group  "root"
                                                                               :source "puppet:///foobar/foo/bar"}}}})))

  (let [resources (:resources catalog)
        new-resources (conj resources (first resources))
        catalog (assoc catalog :resources new-resources)]
    (testing "Duplicate resources should throw error"
      (is (thrown? AssertionError
                   (-> catalog
                       (mapify-resources))))))

  (let [normalize #(-> %
                       (mapify-resources))]
    (testing "Resource normalization edge case handling"
      ; nil resources aren't allowed
      (is (thrown? AssertionError (normalize {:resources nil})))
      ; missing resources aren't allowed
      (is (thrown? AssertionError (normalize {})))
      ; pre-created resource maps aren't allow
      (is (thrown? AssertionError (normalize {:resources {}})))))))


(catalog-before-and-after
 {:data
  {:classes ["settings"],
   :edges [{:relationship "contains",
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
   :tags ["settings"],
   :version 1330995750},
  :document_type "Catalog",
  :metadata {:api_version 1}}

 {:certname "nick-lewis.puppetlabs.lan",
  :api-version 1,
  :puppetdb-version 1,
  :classes #{"settings"},
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
  :tags #{"settings"},
  :version "1330995750"})
