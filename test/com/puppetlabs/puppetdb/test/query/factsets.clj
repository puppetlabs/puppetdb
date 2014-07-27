(ns com.puppetlabs.puppetdb.test.facts
  (:require [com.puppetlabs.puppetdb.query.factsets :as :all]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.test :refer :all]))

(def current-time (to-timestamp (now)))

(deftest test-collapsing-factsets
  (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~d" :value "1" :type "integer" :timestamp current-time}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "true" :type "boolean" :timestamp current-time}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "3.14" :type "float" :timestamp current-time}]]
    (is (= [{:certname "foo.com"
             :environment "DEV"
             :facts {"a" {"b" {"c" "abc"
                               "d" 1
                               "e" true
                               "f" 3.14}}}
             :timestamp current-time}]
           (collapsed-fact-seq test-rows))))

  (testing "laziness of the collapsing fns"
    (let [ten-billion 10000000000]
      (is (= 10
             (count
              (take 10
                    (collapsed-fact-seq
                     (mapcat (fn [certname]
                               [{:certname certname :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time}
                                {:certname certname :environment "DEV" :path "a#~b#~d" :value "1" :type "integer" :timestamp current-time}
                                {:certname certname :environment "DEV" :path "a#~b#~e" :value "3.14" :type "float" :timestamp current-time}
                                {:certname certname :environment "DEV" :path "a#~b#~f" :value "true" :type "boolean" :timestamp current-time}])
                             (map #(str "foo" % ".com") (range 0 ten-billion))))))))))

  (testing "map with a nested vector"
    (let [test-rows [{:certname "foo.com" :environment "DEV"
                      :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~0" :value "1" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~1" :value "3" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~e" :value "true" :type "boolean" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~f" :value "abf" :type "string" :timestamp current-time}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" [1 3]
                                 "e" true
                                 "f" "abf"}}}
               :timestamp current-time}]
             (collapsed-fact-seq test-rows)))))
  (testing "map with a nested vector of maps"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~0#~e#~f#~0" :value "1" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~1#~e#~f#~0" :value "2" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf" :type "string" :timestamp current-time}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" [{"e" {"f" [1]}}
                                      {"e" {"f" [2]}}]
                                 "e" "abe"
                                 "f" "abf"}}}
               :timestamp current-time}]
             (collapsed-fact-seq test-rows)))))

  (testing "json numeric formats"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "10E10" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0" :value "3.14E10" :type "float" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"1\"#~e#~f#~0" :value "1.4e-5" :type "float" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "-10E-5" :type "float" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "-0.25e-5" :type "float" :timestamp current-time}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" 100000000000
                                 "d" {"0" {"e" {"f" [3.14E10]}}
                                      "1" {"e" {"f" [1.4E-5]}}}
                                 "e"  -1.0e-4
                                 "f" -2.5E-6}}}
               :timestamp current-time}]
             (collapsed-fact-seq test-rows)))))

  (testing "map stringified integer keys"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c"
                      :value "abc" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0"
                      :value "1" :type "integer" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~\"1\"#~e#~f#~0" :value "2" :type "integer"
                      :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e"
                      :value "abe" :type "string" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~j"
                      :value nil :type "null" :timestamp current-time}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f"
                      :value "abf" :type "string" :timestamp current-time}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" {"0" {"e" {"f" [1]}}
                                      "1" {"e" {"f" [2]}}}
                                 "e" "abe"
                                 "f" "abf"
                                 "j" nil}}}
               :timestamp current-time}]
             (collapsed-fact-seq test-rows))))))
