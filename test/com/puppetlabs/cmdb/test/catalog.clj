(ns com.puppetlabs.cmdb.test.catalog
  (:use [com.puppetlabs.cmdb.catalog]
        [com.puppetlabs.cmdb.catalog.utils]
        [clojure.test]))

(defn catalog-before-and-after
  "Test that a wire format catalog is equal, post-processing, to the
  indicated cmdb representation"
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
    (is (= (:aliases b) (:aliases a)))
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
             {:type "Class" :title "F[oo]"})))))

(deftest keywordification
  (testing "Changing string-based maps to keyword-based"
    (testing "should error on bad input"
      (is (thrown? AssertionError (keys-to-keywords nil)))
      (is (thrown? AssertionError (keys-to-keywords []))))

    (testing "should work for the base case"
      (is (= (keys-to-keywords {}) {})))

    (testing "should work for a variety of maps"
      (is (= (keys-to-keywords {"foo" 1 "bar" 2})
             {:foo 1 :bar 2}))
      (is (= (keys-to-keywords {"foo" 1 :bar 2})
             {:foo 1 :bar 2}))
      (is (= (keys-to-keywords {:foo 1 :bar 2})
             {:foo 1 :bar 2}))
      (is (= (keys-to-keywords {})
             {})))))

(deftest aliases-building
  (testing "Building the aliases map"
    (testing "should work for the base case"
      (is (= (build-alias-map {:resources {}})
             {:resources {} :aliases {}})))

    (testing "should work for resources with no aliases"
      (is (= (:aliases
              (build-alias-map {:resources {{:type "Foo" :title "bar"} (random-kw-resource "Foo" "bar")}}))
             {})))

    (testing "should work for resources with aliases"
      (is (= (:aliases
              (build-alias-map
               {:resources
                {{:type "Foo" :title "bar"}
                 (random-kw-resource "Foo" "bar" {"parameters" {"alias" "baz"}})}}))
             {{:type "Foo" :title "baz"} {:type "Foo" :title "bar"}})))

    (testing "should work for resources with multiple aliases"
      (is (= (:aliases
              (build-alias-map
               {:resources
                {{:type "Foo" :title "bar"}
                 (random-kw-resource "Foo" "bar" {"parameters" {"alias" ["baz" "boo"]}})}}))
             {{:type "Foo" :title "baz"} {:type "Foo" :title "bar"}
              {:type "Foo" :title "boo"} {:type "Foo" :title "bar"}})))

    (testing "should work for multiple resources"
      (is (= (:aliases
              (build-alias-map
               {:resources
                {{:type "Foo" :title "bar"}
                 (random-kw-resource "Foo" "bar" {"parameters" {"alias" "baz"}})
                 {:type "Goo" :title "gar"}
                 (random-kw-resource "Goo" "gar")}
                }))
             {{:type "Foo" :title "baz"} {:type "Foo" :title "bar"}})))))

(deftest alias-normalization
  (testing "Alias metaparameter normalization"
    (testing "should work for no aliases"
      (is (= (normalize-aliases {:aliases {} :edges #{}})
             {:aliases {} :edges #{}})))

    (testing "should resolve aliases in sources and targets"
      (is (= (:edges (normalize-aliases {:aliases {"a" "real-a"
                                                   "c" "real-c"}
                                         :edges #{{:source "a" :target "b" :relationship :before}
                                                  {:source "b" :target "c" :relationship :before}}}))
             #{{:source "real-a" :target "b" :relationship :before}
               {:source "b" :target "real-c" :relationship :before}})))))

(deftest catalog-restructuring
  (testing "Restructuring catalogs"
    (testing "should work on well-formed input"
      (is (= (restructure-catalog {"data" {"name" "myhost" "version" "12345" "foo" "bar"}
                                   "metadata" {"api_version" 1}})
             {:certname "myhost" :version "12345" :api-version 1 :foo "bar" :cmdb-version CMDB-VERSION})))

    (testing "should error on malformed input"
      (is (thrown? AssertionError (restructure-catalog {})))
      (is (thrown? AssertionError (restructure-catalog nil)))
      (is (thrown? AssertionError (restructure-catalog [])))

      (testing "like non-numeric api versions"
        (is (thrown? AssertionError (restructure-catalog {"data" {"name" "myhost" "version" "12345"}
                                                          "metadata" {"api_version" "123"}}))))

      (testing "like a missing 'data' key"
        (is (thrown? AssertionError (restructure-catalog {"name" "myhost" "version" "12345"
                                                          "metadata" {"api_version" "123"}})))))))


(deftest integrity-checking
  (testing "Catalog integrity checking"
    (testing "should fail when edges mention missing resources"
      (is (thrown? IllegalArgumentException
                   (check-edge-integrity {:edges #{{:source "a" :target "b" :relationship :before}}
                                          :resources {}}))))))

(deftest catalog-normalization
  (let [; Synthesize some fake resources
        catalog {:resources [{"type"       "File"
                              "title"      "/etc/foobar"
                              "exported"   false
                              "line"       1234
                              "file"       "/tmp/foobar.pp"
                              "tags"       ["class" "foobar"]
                              "parameters" {"ensure" "present"
                                            "user"   "root"
                                            "group"  "root"
                                            "source" "puppet:///foobar/foo/bar"}}]}]

    (testing "Resource keywords"
      (is (= (keywordify-resources catalog)
             {:resources [{:type       "File"
                           :title      "/etc/foobar"
                           :exported   false
                           :line       1234
                           :file       "/tmp/foobar.pp"
                           :tags       #{"class" "foobar"}
                           :parameters {"ensure" "present"
                                        "user"   "root"
                                        "group"  "root"
                                        "source" "puppet:///foobar/foo/bar"}}]})))

    (testing "Resource key extraction"
         (is (= (-> catalog
                    (keywordify-resources)
                    (mapify-resources))
                {:resources {{:type "File" :title "/etc/foobar"} {:type       "File"
                                                                  :title      "/etc/foobar"
                                                                  :exported   false
                                                                  :line       1234
                                                                  :file       "/tmp/foobar.pp"
                                                                  :tags       #{"class" "foobar"}
                                                                  :parameters {"ensure" "present"
                                                                               "user"   "root"
                                                                               "group"  "root"
                                                                               "source" "puppet:///foobar/foo/bar"}}}})))

  (let [resources (:resources catalog)
        new-resources (conj resources (first resources))
        catalog (assoc catalog :resources new-resources)]
    (testing "Duplicate resources should throw error"
      (is (thrown? AssertionError
                   (-> catalog
                       (keywordify-resources)
                       (mapify-resources))))))

  (let [normalize #(-> %
                       (keywordify-resources)
                       (mapify-resources))]
    (testing "Resource normalization edge case handling"
      ; nil resources aren't allowed
      (is (thrown? AssertionError (normalize {:resources nil})))
      ; missing resources aren't allowed
      (is (thrown? AssertionError (normalize {})))
      ; pre-created resource maps aren't allow
      (is (thrown? AssertionError (normalize {:resources {}})))))))


(catalog-before-and-after
 {"data"
  {"classes" ["settings"],
   "edges"
   [{"relationship" "contains",
     "source" {"title" "main", "type" "Class"},
     "target" {"title" "/tmp/foo", "type" "File"}}
    {"relationship" "contains",
     "source" {"title" "main", "type" "Stage"},
     "target" {"title" "Settings", "type" "Class"}}
    {"relationship" "contains",
     "source" {"title" "main", "type" "Class"},
     "target" {"title" "/tmp/baz", "type" "File"}}
    {"relationship" "contains",
     "source" {"title" "main", "type" "Class"},
     "target" {"title" "/tmp/bar", "type" "File"}}
    {"relationship" "contains",
     "source" {"title" "main", "type" "Stage"},
     "target" {"title" "main", "type" "Class"}}
    {"relationship" "contains",
     "source" {"title" "main", "type" "Class"},
     "target" {"title" "/tmp/quux", "type" "File"}}
    {"relationship" "required-by",
     "source" {"title" "/tmp/bar", "type" "File"},
     "target" {"title" "/tmp/foo", "type" "File"}}
    {"relationship" "required-by",
     "source" {"title" "/tmp/baz", "type" "File"},
     "target" {"title" "/tmp/foo", "type" "File"}}
    {"relationship" "required-by",
     "source" {"title" "/tmp/quux", "type" "File"},
     "target" {"title" "/tmp/baz", "type" "File"}}
    {"relationship" "required-by",
     "source" {"title" "/tmp/baz", "type" "File"},
     "target" {"title" "/tmp/bar", "type" "File"}}
    {"relationship" "required-by",
     "source" {"title" "/tmp/quux", "type" "File"},
     "target" {"title" "/tmp/bar", "type" "File"}}],
   "name" "nick-lewis.puppetlabs.lan",
   "resources"
   [{"exported" false,
     "file" "/Users/nicklewis/projects/grayskull/test.pp",
     "line" 3,
     "parameters" {"require" ["File[/tmp/bar]" "File[/tmp/baz]"]},
     "tags" ["file" "class"],
     "title" "/tmp/foo",
     "type" "File"}
    {"exported" false,
     "parameters" {},
     "tags" ["class" "settings"],
     "title" "Settings",
     "type" "Class"}
    {"exported" false,
     "file" "/Users/nicklewis/projects/grayskull/test.pp",
     "line" 11,
     "parameters" {"require" "File[/tmp/quux]"},
     "tags" ["file" "class"],
     "title" "/tmp/baz",
     "type" "File"}
    {"exported" false,
     "parameters" {"name" "main"},
     "tags" ["stage"],
     "title" "main",
     "type" "Stage"}
    {"exported" false,
     "file" "/Users/nicklewis/projects/grayskull/test.pp",
     "line" 7,
     "parameters" {"require" ["File[/tmp/baz]" "File[/tmp/quux]"]},
     "tags" ["file" "class"],
     "title" "/tmp/bar",
     "type" "File"}
    {"exported" false,
     "parameters" {"name" "main"},
     "tags" ["class"],
     "title" "main",
     "type" "Class"}
    {"exported" false,
     "file" "/Users/nicklewis/projects/grayskull/test.pp",
     "line" 12,
     "parameters" {},
     "tags" ["file" "class"],
     "title" "/tmp/quux",
     "type" "File"}],
   "tags" ["settings"],
   "version" 1330995750},
  "document_type" "Catalog",
  "metadata" {"api_version" 1}}

 {:aliases {},
  :certname "nick-lewis.puppetlabs.lan",
  :api-version 1,
  :cmdb-version 1,
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
               :file "/Users/nicklewis/projects/grayskull/test.pp",
               :line 3,
               :parameters {"require" ["File[/tmp/bar]" "File[/tmp/baz]"]},
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
               :file "/Users/nicklewis/projects/grayskull/test.pp",
               :line 11,
               :parameters {"require" "File[/tmp/quux]"},
               :tags #{"class" "file"},
               :title "/tmp/baz",
               :type "File"},
              {:type "Stage", :title "main"}
              {:exported false,
               :parameters {"name" "main"},
               :tags #{"stage"},
               :title "main",
               :type "Stage"},
              {:type "File", :title "/tmp/bar"}
              {:exported false,
               :file "/Users/nicklewis/projects/grayskull/test.pp",
               :line 7,
               :parameters {"require" ["File[/tmp/baz]" "File[/tmp/quux]"]},
               :tags #{"class" "file"},
               :title "/tmp/bar",
               :type "File"},
              {:type "Class", :title "main"}
              {:exported false,
               :parameters {"name" "main"},
               :tags #{"class"},
               :title "main",
               :type "Class"},
              {:type "File", :title "/tmp/quux"}
              {:exported false,
               :file "/Users/nicklewis/projects/grayskull/test.pp",
               :line 12,
               :parameters {},
               :tags #{"class" "file"},
               :title "/tmp/quux",
               :type "File"}},
  :tags #{"settings"},
  :version "1330995750"})
