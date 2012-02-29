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

(deftest edge-normalization
  (testing "Containment edge normalization"
    (testing "should work for the base case"
      (is (= (normalize-containment-edges {:edges []}) {:edges #{}})))

    (testing "should error on bad input"
      (is (thrown? AssertionError (normalize-containment-edges {:edges nil})))
      (is (thrown? AssertionError (normalize-containment-edges {:edges [{"source" "foo"}]})))
      (is (thrown? AssertionError (normalize-containment-edges {:edges [{"source" "foo" "target" "bar"}]})))
      (is (thrown? AssertionError (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "bar"}]})))
      (is (thrown? AssertionError (normalize-containment-edges {:edges [{"source" nil "target" "bar"}]})))
      (is (thrown? AssertionError (normalize-containment-edges {:edges [{"source" "Class[foo]" "meh" "Class[bar]"}]}))))

    (testing "should work for well-formed edges"
      (is (= (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "Class[bar]"}]})
             {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}}})))

    (testing "should work for multiple edges"
      (is (= (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "Class[bar]"}
                                                   {"source" "Class[baz]" "target" "Class[goo]"}]})
             {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}
                       {:source {:type "Class" :title "baz"} :target {:type "Class" :title "goo"} :relationship :contains}}})))

    (testing "should squash duplicates"
      (is (= (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "Class[bar]"}
                                                   {"source" "Class[foo]" "target" "Class[bar]"}]})
             {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}}})))

    (testing "should create resources for things that have edges, but aren't listed in the :resources list"
      (is (= (-> {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}} :resources []}
                 (add-resources-for-edges)
                 (:resources)
                 (set))
             #{{:type "Class" :title "foo" :exported false} {:type "Class" :title "bar" :exported false}})))))

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


(deftest dependency-normalization
  (testing "Dependency normalization for a resource"
    (is (= (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"before" "Class[Bar]"}}))
           [{:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Bar"} :relationship :before}]))

    (is (= (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"require" "Class[Bar]"}}))
           [{:source {:type "Class" :title "Bar"} :target {:type "Class" :title "Foo"} :relationship :required-by}]))

    (is (= (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"subscribe" "Class[Bar]"}}))
           [{:source {:type "Class" :title "Bar"} :target {:type "Class" :title "Foo"} :relationship :subscription-of}]))

    (is (= (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"notify" "Class[Bar]"}}))
           [{:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Bar"} :relationship :notifies}]))

    (is (= (build-dependencies-for-resource (random-kw-resource "Class" "Foo"))
           []))

    (testing "should handle multi-valued attributes"
      (is (= (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"before" ["Class[Bar]", "Class[Goo]"]}}))
             [{:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Bar"} :relationship :before}
              {:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Goo"} :relationship :before}])))

    (testing "should error on bad input"
      ; Must force eval using "vec", as we otherwise get back a lazy seq
      (is (thrown? AssertionError (vec (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"notify" "meh"}}))))))))

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
 {"metadata" {"api_version" 1}
  "data" {"name"      "myhost.mydomain.com"
          "version"   123456789
          "tags"      ["class" "foobar"]
          "classes"   ["foobar"]
          "edges"     [{"source" "Class[foobar]" "target" "File[/etc/foobar]"}
                       {"source" "Class[foobar]" "target" "File[/etc/foobar/baz]"}]
          "resources" [{"type"       "File"
                        "title"      "/etc/foobar"
                        "exported"   false
                        "tags"       ["file" "class" "foobar"]
                        "parameters" {"ensure" "directory"
                                      "alias" "foobar"
                                      "group" "root"
                                      "user" "root"}}
                       {"type"       "File"
                        "title"      "/etc/foobar/baz"
                        "exported"   false
                        "tags"       ["file" "class" "foobar"]
                        "parameters" {"ensure" "directory"
                                      "group" "root"
                                      "user" "root"
                                      "require" "File[foobar]"}}]}}
 {:certname "myhost.mydomain.com"
  :cmdb-version CMDB-VERSION
  :api-version 1
  :version "123456789"
  :tags #{"class" "foobar"}
  :classes #{"foobar"}
  :aliases {{:type "File" :title "foobar"} {:type "File" :title "/etc/foobar"}}
  :edges #{{:source {:type "Class" :title "foobar"}
            :target {:type "File" :title "/etc/foobar"}
            :relationship :contains}
           {:source {:type "Class" :title "foobar"}
            :target {:type "File" :title "/etc/foobar/baz"}
            :relationship :contains}
           {:source {:type "File" :title "/etc/foobar"}
            :target {:type "File" :title "/etc/foobar/baz"}
            :relationship :required-by}}
  :resources {{:type "Class" :title "foobar"} {:type "Class" :title "foobar" :exported false}
              {:type "File" :title "/etc/foobar"} {:type       "File"
                                                   :title      "/etc/foobar"
                                                   :exported   false
                                                   :tags       #{"file" "class" "foobar"}
                                                   :parameters {"ensure" "directory"
                                                                "alias"  "foobar"
                                                                "group"  "root"
                                                                "user"   "root"}}
              {:type "File" :title "/etc/foobar/baz"} {:type       "File"
                                                       :title      "/etc/foobar/baz"
                                                       :exported   false
                                                       :tags       #{"file" "class" "foobar"}
                                                       :parameters {"ensure"  "directory"
                                                                    "group"   "root"
                                                                    "user"    "root"
                                                                    "require" "File[foobar]"}}}})
