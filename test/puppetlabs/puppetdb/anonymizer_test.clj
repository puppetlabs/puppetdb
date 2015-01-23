(ns puppetlabs.puppetdb.anonymizer-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.anonymizer :refer :all]
            [puppetlabs.kitchensink.core :refer [boolean?]]
            [puppetlabs.kitchensink.core :as ks]))

(def anon-true {"context" {} "anonymize" true})
(def anon-false {"context" {} "anonymize" false})

(deftest test-pattern-string?
  (testing "should return true if pattern"
    (is (pattern-string? "/asdf/")))
  (testing "should return false if not"
    (is (not (pattern-string? "asdf")))))

(deftest test-pattern->regexp
  (testing "should convert a string of /asdf/ to regexp"
    (is (re-find (pattern->regexp "/asdf/") "asdf"))))

(deftest test-matcher-match?
  (testing "test it matches with a regexp"
    (is (true? (matcher-match? "/foo/" "foobarbaz"))))
  (testing "test it does not match with a regexp"
    (is (false? (matcher-match? "/barry/" "foobarbaz"))))
  (testing "test it does not match with a regexp if value is nil"
    (is (false? (matcher-match? "/barry/" nil))))
  (testing "test it matches with an array of regexp"
    (is (true? (matcher-match? ["/foo/" "/something/"] "foobarbaz"))))
  (testing "test it does not match with an array of regexp"
    (is (false? (matcher-match? ["/barry/" "/suzan/"] "foobarbaz"))))
  (testing "test it matches with an exact string"
    (is (true? (matcher-match? "foobarbaz" "foobarbaz"))))
  (testing "test it does not matches with an exact string"
    (is (false? (matcher-match? "barry" "foobarbaz"))))
  (testing "test it matches with an array of strings"
    (is (true? (matcher-match? ["foo" "bar" "baz"] "bar"))))
  (testing "test it does not match with an array of strings"
    (is (false? (matcher-match? ["foo" "bar" "baz"] "barry"))))
  (testing "test it matches with an array of mixed strings and regexps"
    (is (true? (matcher-match? ["foo" "/bar/" "baz"] "bar")))
    (is (true? (matcher-match? ["foo" "bar" "/baz/"] "bar")))))

(deftest test-rule-match?
  (testing "no context in rule should match anything"
    (let [rule    {"context" {} "anonymize" true}
          context {"node" "foo" "type" "User" "title" "/tmp/foo"}]
      (is (true? (rule-match? rule context)))))

  (testing "match against node should match"
    (let [rule    {"context" {"node" "foo"} "anonymize" true}
          context {"node" "foo" "type" "User" "title" "/tmp/foo"}]
      (is (true? (rule-match? rule context)))))

  (testing "match against node with array should match"
    (let [rule    {"context" {"node" ["foobarbaz", "baz"]} "anonymize" true}
          context {"node" "foobarbaz" "type" "User" "title" "/tmp/foo"}]
      (is (true? (rule-match? rule context)))))

  (testing "match against element not in context should not match"
    (let [rule    {"context" {"type" "User"} "anonymize" true}
          context {"node" "foobarbaz"}]
      (is (false? (rule-match? rule context)))))

  (testing "rule with incorrect value should not match"
    (let [rule    {"context" {"type" "User"} "anonymize" true}
          context {"node" "foobarbaz" "type" "File"}]
      (is (false? (rule-match? rule context)))))

  (testing "match against node with regexp should match"
    (let [rule    {"context" {"node" "/bar/"} "anonymize" true}
          context {"node" "foobarbaz" "type" "User" "title" "/tmp/foo"}]
      (is (true? (rule-match? rule context))))))

(deftest test-rules-match
  (testing "ensure we match on the first rule block"
    (let [rules  [{"context" {"node" "/forge/"} "anonymize" true}
                  {"context" {"node" "/puppetlabs/"} "anonymize" false}
                  {"context" {} "anonymize" true}]
          context {"node" "forge.puppetlabs.com"}]
      (is (true? (rules-match rules context)))))

  (testing "ensure we match on the second rule block"
    (let [rules  [{"context" {"node" "/forge/"} "anonymize" true}
                  {"context" {"node" "/puppetlabs/"} "anonymize" false}
                  {"context" {} "anonymize" true}]
          context {"node" "heyjude.puppetlabs.com"}]
      (is (false? (rules-match rules context)))))

  (testing "ensure we work with the default trailing rule"
    (let [rules  [{"context" {"node" "/forge/"} "anonymize" false}
                  {"context" {"node" "/puppetlabs/"} "anonymize" false}
                  {"context" {} "anonymize" true}]
          context {"node" "myhost.mynode.com"}]
      (is (true? (rules-match rules context))))))

(deftest test-anonymize-leaf-node
  (testing "should return the same string twice"
    (is (= (anonymize-leaf-memoize :node "test string") (anonymize-leaf-memoize :node "test string"))))

  (testing "should return a string 30 characters long"
    (is (string? (anonymize-leaf-memoize :node "good old string")))
    (is (= 30 (count (anonymize-leaf-memoize :node "good old string"))))))

(deftest test-anonymize-leaf-type
  (testing "should return the same string twice"
    (is (= (anonymize-leaf-memoize :type "test string") (anonymize-leaf-memoize :type "test string"))))

  (testing "should return a string 10 characters long"
    (is (string? (anonymize-leaf-memoize :type "good old string")))
    (is (= 10 (count (anonymize-leaf-memoize :type "good old string"))))))

(deftest test-anonymize-leaf-title
  (testing "should return the same string twice"
    (is (= (anonymize-leaf-memoize :title "test string") (anonymize-leaf-memoize :title "test string"))))

  (testing "should return a string 15 characters long"
    (is (string? (anonymize-leaf-memoize :title "good old string")))
    (is (= 15 (count (anonymize-leaf-memoize :title "good old string"))))))

(deftest test-anonymize-leaf-parameter-name
  (testing "should return the same string twice"
    (is (= (anonymize-leaf-memoize :parameter-name "test string") (anonymize-leaf-memoize :parameter-name "test string"))))

  (testing "should return a string 10 characters long"
    (is (string? (anonymize-leaf-memoize :parameter-name "good old string")))
    (is (= 10 (count (anonymize-leaf-memoize :parameter-name "good old string"))))))

(deftest test-anonymize-leaf-value
  (testing "should return the same string twice"
    (is (= (anonymize-leaf-memoize :parameter-value "test string") (anonymize-leaf-memoize :parameter-value "test string"))))
  (testing "should return the same string twice"
    (is  (=  (anonymize-leaf-memoize :fact-value  {"a" "b"})  (anonymize-leaf-memoize :fact-value  {"a" "b"}))))
  (testing "should return a string 30 chars long when passed a string"
    (is (= 30 (count (anonymize-leaf-value "good old string"))))
    (is (string? (anonymize-leaf-value "some string"))))
  (testing "should return a boolean when passed a boolean"
    (is (boolean? (anonymize-leaf-value true))))
  (testing "should return an integer when passed an integer"
    (is (integer? (anonymize-leaf-value 100))))
  (testing "should return an float when passed an float"
    (is (float? (anonymize-leaf-value 3.14))))
  (testing "should return a vector when passed a vector"
    (is (vector? (anonymize-leaf-value ["asdf" "asdf"]))))
  (testing "should return a map when passed a map"
    (is (map? (anonymize-leaf-value {"foo" "bar"}))))
  (testing "maps should retain their child types"
    (let [mymap {"a" {"b" 1} "c" 3.14 "d" [1 2] "e" 3}]
      (is (= (sort (map (comp str type) mymap))
             (sort (map (comp str type) (anonymize-leaf-value mymap))))))))

(deftest test-anonymize-leaf-message
  (testing "should return a string 50 characters long"
    (is (string? (anonymize-leaf-memoize :message "good old string")))
    (is (= 50 (count (anonymize-leaf-memoize :message "good old string"))))))

(deftest test-anonymize-leaf-file
  (testing "should return a string 54 characters long"
    (is (string? (anonymize-leaf-memoize :file "good old string")))
    (is (= 54 (count (anonymize-leaf-memoize :file "good old string")))))

  (testing "starting with a /etc/puppet/modules ending in .pp"
    (is (re-find #"^/etc/puppet/modules/.+/manifests/.+\.pp$" (anonymize-leaf-memoize :file "good old string")))))

(deftest test-anonymize-leaf-line
  (testing "should return a number"
    (is (integer? (anonymize-leaf-memoize :line 10)))))

(deftest test-anonymize-leaf-transaction-uuid
  (testing "should return a string 36 characters long"
    (is (string? (anonymize-leaf-memoize :transaction_uuid "0fc3241f-35f7-43c8-bcbb-d79fb626be3f")))
    (is (= 36 (count (anonymize-leaf-memoize :transaction_uuid "0fc3241f-35f7-43c8-bcbb-d79fb626be3f")))))
  (testing "should not return the input string"
    (is (not (= "0fc3241f-35f7-43c8-bcbb-d79fb626be3f" (anonymize-leaf-memoize :transaction_uuid "0fc3241f-35f7-43c8-bcbb-d79fb626be3f"))))))

(deftest test-anonymize-leaf
  (testing "should return a random string when type 'node' and rule returns true"
    (let [anon-config {"rules" {"node" [anon-true]}}]
      (is (string? (anonymize-leaf "mynode" :node {} anon-config)))
      (is (not (= "mynode" (anonymize-leaf "mynode" :node {} anon-config))))))
  (testing "should not return a random string when type 'node' and rule returns false"
    (let [anon-config {"rules" {"node" [anon-false]}}]
      (is (string? (anonymize-leaf "mynode" :node {} anon-config)))
      (is (= "mynode" (anonymize-leaf "mynode" :node {} anon-config)))))
  (testing "should not return a random string when type 'node' and rule returns false"
    (let [anon-config {"rules" {"node" [{"context" {"node" "mynode"} "anonymize" false}]}}]
      (is (string? (anonymize-leaf "mynode" :node {"node" "mynode"} anon-config)))
      (is (= "mynode" (anonymize-leaf "mynode" :node {"node" "mynode"} anon-config)))))
  (testing "should anonymize by default when there is no rule match"
    (let [anon-config {}]
      (is (string? (anonymize-leaf "mynode" :node {} anon-config)))
      (is (not (= "mynode" (anonymize-leaf "mynode" :node {} anon-config)))))))

(deftest test-anonymize-reference
  (testing "should anonymize both type and title based on rule"
    (let [anon-config {"rules" {"type" [anon-true] "title" [anon-true]}}
          result      (anonymize-reference "File[/etc/foo]" {} anon-config)]
      (is (string? result))
      (is (re-find #"^[A-Z][a-z]+\[.+\]$" result))))

  (testing "should anonymize just the title if rule specifies"
    (let [anon-config {"rules" {"type" [anon-false] "title" [anon-true]}}
          result      (anonymize-reference "File[/etc/foo]" {} anon-config)]
      (is (string? result))
      (is (re-find #"^File\[.+\]$" result)))))

(deftest test-anonymize-references
  (testing "should return a collection when passed a colection"
    (is (coll? (anonymize-references ["File[/etc/foo]" "Host[localhost]"] {} {}))))
  (testing "should return a string when passed a single string"
    (is (string? (anonymize-references "File[/etc/foo]" {} {})))))

(deftest test-anonymize-aliases
  (testing "should return a collection when passed a colection"
    (is (coll? (anonymize-aliases ["test1" "test2"] {} {}))))
  (testing "should return a string when passed a single string"
    (is (string? (anonymize-aliases "test1" {} {})))))

(deftest test-anonymizer-parameter
  (testing "should return a collection of 2"
    (is (coll? (anonymize-parameter ["param" "value"] {} {})))
    (is (= 2 (count (anonymize-parameter ["param" "value"] {} {}))))))

(deftest test-anonymizer-parameters
  (testing "should return a collection of the same length"
    (let [input  {"param1" "value", "param2" "value"}
          result (anonymize-parameters input {} {})]
      (is (map? result))
      (is (= 2 (count result)))
      (is (not (= input result))))))

(deftest test-capitalize-resource-type
  (testing "should change a resource type to upcase format like Foo::Bar"
    (let [input  "foo::bar"
          result (capitalize-resource-type input)]
      (is (= "Foo::Bar" result)))))

(deftest test-anonymize-tag
  (testing "should anonymize a tag"
    (let [input  "my::class"
          result (anonymize-tag input {} {})]
      (is (not (= input result)))
      (is (string? result)))))

(deftest test-anonymize-tags
  (testing "return a collection"
    (is (coll? (anonymize-tags ["first" "second"] {} {})))
    (is (= 2 (count (anonymize-tags ["first" "second"] {} {}))))))

(deftest test-anonymize-edge
  (testing "should return anonymized data"
    (let [test-edge {"source" {"title" "/etc/ntp.conf"
                               "type"  "File"}
                     "target" {"title" "ntp"
                               "type"  "Package"}
                     "relationship" "requires"}]
      (is (map? (anonymize-edge test-edge {} {}))))))

(deftest test-anonymize-edges
  (testing "should handle a collection of edges"
    (let [test-edge {"source" {"title" "/etc/ntp.conf"
                               "type"  "File"}
                     "target" {"title" "ntp"
                               "type"  "Package"}
                     "relationship" "requires"}]
      (is (coll? (anonymize-edges [test-edge] {} {})))
      (is (= 1 (count (anonymize-edges [test-edge] {} {})))))))

(deftest test-anonymize-resource
  (testing "should handle a resource"
    (let [test-resource {"parameters" {"ensure" "present"}
                         "exported"   true
                         "file"       "/etc/puppet/modules/foo/manifests/init.pp"
                         "line"       25000000
                         "tags"       ["package"]
                         "title"      "foo"
                         "type"       "Package"}
          result        (anonymize-resource test-resource {} {})]
      (is (map? result))
      (is (= #{"parameters" "exported" "line" "title" "tags" "type" "file"} (ks/keyset result)))))
  (testing "should handle nil for file and line"
    (let [test-resource {"parameters" {"ensure" "present"}
                         "exported"   true
                         "tags"       ["package"]
                         "title"      "foo"
                         "type"       "Package"}
          result        (anonymize-resource test-resource {} {})]
      (is (map? result))
      (is (= #{"parameters" "exported" "title" "tags" "type"} (ks/keyset result))))))

(deftest test-anonymize-resources
  (testing "should handle a resource"
    (let [test-resource {"parameters" {"ensure" "present"}
                         "exported"   true
                         "file"       "/etc/puppet/modules/foo/manifests/init.pp"
                         "line"       25000000
                         "tags"       ["package"]
                         "title"      "foo"
                         "type"       "Package"}
          result (first (anonymize-resources [test-resource] {} {}))]

      (is (= (ks/keyset test-resource)
             (ks/keyset result)))

      (are [k] (not= (get test-resource k)
                     (get result k))
           "parameters"
           "line"
           "title"
           "tags"
           "type"
           "file"))))

(deftest test-anonymize-resource-event
  (testing "should handle a resource event"
    (let [test-event {"status"           "noop"
                      "timestamp"        "2013-03-04T19:56:34.000Z"
                      "resource_title"   "foo"
                      "property"         "ensure"
                      "message"          "Ensure was absent now present"
                      "new_value"        "present"
                      "old_value"        "absent"
                      "resource_type"    "Package"
                      "file"             "/home/user/site.pp"
                      "line"             1
                      "containment_path" ["Stage[main]" "Foo" "Notify[hi]"]}
          anonymized-event (anonymize-resource-event test-event {} {})]
      (is (map? anonymized-event))
      (is (= (keys test-event) (keys anonymized-event)))))
  (testing "should handle a resource event with optionals"
    (let [test-event {"status"           "noop"
                      "timestamp"        "2013-03-04T19:56:34.000Z"
                      "resource_title"   "foo"
                      "property"         "ensure"
                      "message"          "Ensure was absent now present"
                      "new_value"        "present"
                      "old_value"        "absent"
                      "resource_type"    "Package"
                      "file"             nil
                      "line"             nil
                      "containment_path" nil}
          anonymized-event (anonymize-resource-event test-event {} {})]
      (is (map? anonymized-event))
      (is (= (keys test-event) (keys anonymized-event))))))

(deftest test-anonymize-resource-events
  (testing "should handle a resource event"
    (let [test-event {"status"         "noop"
                      "timestamp"      "2013-03-04T19:56:34.000Z"
                      "resource_title" "foo"
                      "property"       "ensure"
                      "message"        "Ensure was absent now present"
                      "new_value"      "present"
                      "old_value"      "absent"
                      "resource_type"  "Package"}]
      (is (coll? (anonymize-resource-events [test-event] {} {}))))))
