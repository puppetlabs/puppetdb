(ns com.puppetlabs.cmdb.test.catalog
  (:use [com.puppetlabs.cmdb.catalog])
  (:use [clojure.test]
        [midje.sweet]))

;;
;; Helper functions (TODO: move these into a test utils namespace?)
;;

(defn random-string
  "Generate a random string of optional length"
  ([] (random-string (inc (rand-int 10))))
  ([length]
     (let [ascii-codes (concat (range 48 58) (range 66 91) (range 97 123))]
       (apply str (repeatedly length #(char (rand-nth ascii-codes)))))))

(defn random-bool [] (rand-nth [true false]))

(defn random-resource
  "Generate a random resource. Can optionally specify type/title, as
  well as any attribute overrides.

  Note that is _parameters_ is given as an override, the supplied
  parameters are merged in with the randomly generated set."
  ([] (random-resource (random-string) (random-string)))
  ([type title] (random-resource type title {}))
  ([type title overrides]
     (let [extra-params (overrides "parameters")
           overrides    (dissoc overrides "parameters")
           r            {"type"       type
                         "title"      title
                         "exported"   (random-bool)
                         "file"       (random-string)
                         "line"       (rand-int 1000)
                         "tags"       (into #{} (repeatedly (rand-int 10) #(random-string)))
                         "parameters" (merge
                                       (into {} (repeatedly (rand-int 10) #(vector (random-string) (random-string))))
                                       extra-params)}]
       (merge r overrides))))

;; A version of random-resource that returns resources with keyword
;; keys instead of strings
(def random-kw-resource (comp keys-to-keywords random-resource))

(defn catalog-before-and-after
  "Test that a wire format catalog is equal, post-processing, to the
  indicated cmdb representation"
  [before after]
  (let [b (parse-from-json-obj before)
        a after]
    (facts (:host b) => (:host a)
           (:version b) => (:version a)
           (:api-version b) => (:api-version a)
           (:tags b) => (:tags a)
           (:classes b) => (:classes a)
           (:edges b) => (:edges a)
           (:edges b) => (:edges a)
           (:resources b) => (:resources a))))

;;
;; And now, tests...
;;

(facts "Parsing resource strings"
       (resource-spec-to-map "Class[Foo]") => {:type "Class" :title "Foo"}
       (resource-spec-to-map "Class[Foo") => (throws AssertionError)
       (resource-spec-to-map "ClassFoo]") => (throws AssertionError)
       (resource-spec-to-map "ClassFoo") => (throws AssertionError)
       (resource-spec-to-map "Class[F[oo]]") => {:type "Class" :title "F[oo]"}
       (resource-spec-to-map nil) => (throws AssertionError))

(facts "Changing string-based maps to keyword-based"
       (keys-to-keywords {"foo" 1 "bar" 2}) => {:foo 1 :bar 2}
       (keys-to-keywords {"foo" 1 :bar 2}) => {:foo 1 :bar 2}
       (keys-to-keywords {:foo 1 :bar 2}) => {:foo 1 :bar 2}
       (keys-to-keywords {}) => {}
       (keys-to-keywords nil) => (throws AssertionError)
       (keys-to-keywords []) => (throws AssertionError))

(facts "Containment edge normalization"
       (normalize-containment-edges {:edges []}) => {:edges #{}}
       (normalize-containment-edges {:edges []}) => {:edges #{}}

       ; Malformed edges
       (normalize-containment-edges {:edges nil}) => (throws AssertionError)
       (normalize-containment-edges {:edges [{"source" "foo"}]}) => (throws AssertionError)
       (normalize-containment-edges {:edges [{"source" "foo" "target" "bar"}]}) => (throws AssertionError)
       (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "bar"}]}) => (throws AssertionError)
       (normalize-containment-edges {:edges [{"source" nil "target" "bar"}]}) => (throws AssertionError)
       (normalize-containment-edges {:edges [{"source" "Class[foo]" "meh" "Class[bar]"}]}) => (throws AssertionError)

       ; Well-formed edges
       (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "Class[bar]"}]})
       => {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}}}

       ; Multiple edges should work
       (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "Class[bar]"}
                                             {"source" "Class[baz]" "target" "Class[goo]"}]})
       => {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}
                    {:source {:type "Class" :title "baz"} :target {:type "Class" :title "goo"} :relationship :contains}}}

       ; Squash duplicates
       (normalize-containment-edges {:edges [{"source" "Class[foo]" "target" "Class[bar]"}
                                             {"source" "Class[foo]" "target" "Class[bar]"}]})
       => {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}}}

       ; Resources get created for things that have edges, but aren't listed in the :resources list
       (:resources
        (add-resources-for-edges {:edges #{{:source {:type "Class" :title "foo"} :target {:type "Class" :title "bar"} :relationship :contains}}
                                  :resources []}))
       => (contains [{:type "Class" :title "foo" :exported false} {:type "Class" :title "bar" :exported false}] :in-any-order))

(facts "Restructuring catalogs"
       (restructure-catalog {"data" {"name" "myhost" "version" "12345" "foo" "bar"}
                             "metadata" {"api_version" 1}})
       => {:host "myhost" :version "12345" :api-version 1 :foo "bar" :cmdb-version CMDB-VERSION}

       ; Non-numeric api version
       (restructure-catalog {"data" {"name" "myhost" "version" "12345"}
                             "metadata" {"api_version" "123"}})
       => (throws AssertionError)

       ; Missing "data" key
       (restructure-catalog {"name" "myhost" "version" "12345"
                             "metadata" {"api_version" "123"}})
       => (throws AssertionError)

       (restructure-catalog {}) => (throws AssertionError)
       (restructure-catalog nil) => (throws AssertionError)
       (restructure-catalog []) => (throws AssertionError))

(facts "Dependency normalization for a resource"
       (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"before" "Class[Bar]"}}))
       => (just [{:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Bar"} :relationship :before}])

       (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"require" "Class[Bar]"}}))
       => (just [{:source {:type "Class" :title "Bar"} :target {:type "Class" :title "Foo"} :relationship :required-by}])

       (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"subscribe" "Class[Bar]"}}))
       => (just [{:source {:type "Class" :title "Bar"} :target {:type "Class" :title "Foo"} :relationship :subscription-of}])

       (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"notify" "Class[Bar]"}}))
       => (just [{:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Bar"} :relationship :notifies}])

       (build-dependencies-for-resource (random-kw-resource "Class" "Foo"))
       => []

       ; Handle multi-valued attributes
       (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"before" ["Class[Bar]", "Class[Goo]"]}}))
       => (just [{:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Bar"} :relationship :before}
                 {:source {:type "Class" :title "Foo"} :target {:type "Class" :title "Goo"} :relationship :before}])

       ; Malformed parameters
       (build-dependencies-for-resource (random-kw-resource "Class" "Foo" {"parameters" {"notify" "meh"}}))
       => (throws AssertionError))

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

  (facts "Resource keywords"
         (keywordify-resources catalog)
         =>
         {:resources [{:type       "File"
                       :title      "/etc/foobar"
                       :exported   false
                       :line       1234
                       :file       "/tmp/foobar.pp"
                       :tags       #{"class" "foobar"}
                       :parameters {"ensure" "present"
                                    "user"   "root"
                                    "group"  "root"
                                    "source" "puppet:///foobar/foo/bar"}}]})

  (facts "Resource key extraction"
         (-> catalog
             (keywordify-resources)
             (mapify-resources))
         =>
         {:resources {{:type "File" :title "/etc/foobar"} {:type       "File"
                                                           :title      "/etc/foobar"
                                                           :exported   false
                                                           :line       1234
                                                           :file       "/tmp/foobar.pp"
                                                           :tags       #{"class" "foobar"}
                                                           :parameters {"ensure" "present"
                                                                        "user"   "root"
                                                                        "group"  "root"
                                                                        "source" "puppet:///foobar/foo/bar"}}}})

  (let [resources (:resources catalog)
        new-resources (conj resources (first resources))
        catalog (assoc catalog :resources new-resources)]
    (facts "Duplicate resources should throw error"
           (-> catalog
               (keywordify-resources)
               (mapify-resources))
           =>
           (throws AssertionError)))

  (let [normalize #(-> %
                       (keywordify-resources)
                       (mapify-resources))]
    (facts "Resource normalization edge case handling"
           ; nil resources aren't allowed
           (normalize {:resources nil}) => (throws AssertionError)
           ; missing resources aren't allowed
           (normalize {}) => (throws AssertionError)
           ; pre-created resource maps aren't allow
           (normalize {:resources {}}) => (throws AssertionError))))


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
                                      "group" "root"
                                      "user" "root"}}
                       {"type"       "File"
                        "title"      "/etc/foobar/baz"
                        "exported"   false
                        "tags"       ["file" "class" "foobar"]
                        "parameters" {"ensure" "directory"
                                      "group" "root"
                                      "user" "root"
                                      "require" "File[/etc/foobar]"}}]}}
 {:host "myhost.mydomain.com"
  :cmdb-version CMDB-VERSION
  :api-version 1
  :version 123456789
  :tags #{"class" "foobar"}
  :classes #{"foobar"}
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
                                                                "group"  "root"
                                                                "user"   "root"}}
              {:type "File" :title "/etc/foobar/baz"} {:type       "File"
                                                       :title      "/etc/foobar/baz"
                                                       :exported   false
                                                       :tags       #{"file" "class" "foobar"}
                                                       :parameters {"ensure"  "directory"
                                                                    "group"   "root"
                                                                    "user"    "root"
                                                                    "require" "File[/etc/foobar]"}}}})
