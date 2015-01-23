(ns puppetlabs.puppetdb.query.factsets-test
  (:require [puppetlabs.puppetdb.query.factsets :refer :all]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.test :refer :all]))

(def current-time (to-timestamp (now)))

(deftest test-structured-data-seq
  (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c"
                    :value "abc" :type "string" :timestamp current-time :value_integer nil
                    :value_float nil :producer_timestamp current-time :hash "1234"}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~d"
                    :value nil :type "integer" :timestamp current-time
                    :value_integer 1 :value_float nil :producer_timestamp current-time :hash  "1234"}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~e"
                    :value "true" :type "boolean" :timestamp current-time
                    :value_integer nil :value_float nil :producer_timestamp current-time :hash "1234"}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~f"
                    :value_float 3.14 :type "float" :timestamp current-time
                    :value_integer nil :value nil :producer_timestamp current-time :hash "1234"}]]
    (is (= [{:certname "foo.com"
             :environment "DEV"
             :facts {"a" {"b" {"c" "abc"
                               "d" 1
                               "e" true
                               "f" 3.14}}}
             :timestamp current-time
             :producer_timestamp current-time
             :hash "1234"}]
           (structured-data-seq :v4 test-rows))))

  (testing "laziness of the collapsing fns"
    (let [ten-billion 10000000000]
      (is (= 10
             (count
               (take 10
                     (structured-data-seq
                       :v4 (mapcat
                             (fn [certname]
                               [{:certname certname :environment "DEV" :path "a#~b#~c"
                                 :value "abc" :type "string" :timestamp current-time
                                 :value_integer nil :value_float nil
                                 :producer_timestamp current-time :hash "1234"}
                                {:certname certname :environment "DEV" :path "a#~b#~d"
                                 :value_integer 1 :type "integer" :timestamp current-time
                                 :value nil :value_float nil
                                 :producer_timestamp current-time :hash "1234"}
                                {:certname certname :environment "DEV" :path "a#~b#~e"
                                 :value_float 3.14 :type "float" :timestamp current-time
                                 :value_integer nil :value nil
                                 :producer_timestamp current-time :hash "1234"}
                                {:certname certname :environment "DEV" :path "a#~b#~f"
                                 :value "true" :type "boolean" :timestamp current-time
                                 :value_integer nil :value_float nil
                                 :producer_timestamp current-time :hash "1234"}])
                             (map #(str "foo" % ".com") (range 0 ten-billion))))))))))

  (testing "map with a nested vector"
    (let [test-rows [{:certname "foo.com" :environment "DEV"
                      :path "a#~b#~c" :value "abc" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~0" :value_integer 1 :type "integer" :timestamp current-time
                      :value nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~1" :value_integer 3 :type "integer" :timestamp current-time
                      :value nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~e" :value "true" :type "boolean" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~f" :value "abf" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" [1 3]
                                 "e" true
                                 "f" "abf"}}}
               :timestamp current-time
               :producer_timestamp current-time
               :hash "1234"}]
             (structured-data-seq :v4 test-rows)))))

  (testing "map with a nested vector of maps"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c"
                      :value "abc" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~0#~e#~f#~0"
                      :value_integer 1 :type "integer" :timestamp current-time
                      :value nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~1#~e#~f#~0"
                      :value_integer 2 :type "integer" :timestamp current-time
                      :value nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e"
                      :value "abe" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f"
                      :value "abf" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" [{"e" {"f" [1]}}
                                      {"e" {"f" [2]}}]
                                 "e" "abe"
                                 "f" "abf"}}}
               :timestamp current-time
               :producer_timestamp current-time
               :hash "1234"}]
             (structured-data-seq :v4 test-rows)))))

  (testing "ensure that nil-valued hashes are legal"
    (let [test-row [{:certname "foo.com" :environment "DEV" :path "a#~b#~c"
                      :value "abc" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash nil}]]
      (is (= [{:facts {"a" {"b" {"c" "abc"}}}
               :producer_timestamp current-time
               :timestamp current-time :environment "DEV"
               :certname "foo.com", :hash nil}]
             (structured-data-seq :v4 test-row)))))

  (testing "json numeric formats"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c"
                      :value_integer 100000000000 :type "integer" :timestamp current-time
                      :value nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0"
                      :value_float 3.14E10 :type "float" :timestamp current-time
                      :value_integer nil :value nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"1\"#~e#~f#~0"
                      :value_float 1.4e-5 :type "float" :timestamp current-time
                      :value_integer nil :value nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e"
                      :value_float -10E-5 :type "float" :timestamp current-time
                      :value_integer nil :value nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f"
                      :value_float -0.25e-5 :type "float" :timestamp current-time
                      :value_integer nil :value nil
                      :producer_timestamp current-time :hash "1234"}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" 100000000000
                                 "d" {"0" {"e" {"f" [3.14E10]}}
                                      "1" {"e" {"f" [1.4E-5]}}}
                                 "e"  -1.0e-4
                                 "f" -2.5E-6}}}
               :timestamp current-time
               :producer_timestamp current-time
               :hash "1234"}]
             (structured-data-seq :v4 test-rows)))))

  (testing "map stringified integer keys"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c"
                      :value "abc" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0"
                      :value_integer 1 :type "integer" :timestamp current-time
                      :value nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV"
                      :path "a#~b#~d#~\"1\"#~e#~f#~0" :value_integer 2 :type "integer"
                      :timestamp current-time :value nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e"
                      :value "abe" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~j"
                      :value nil :type "null" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f"
                      :value "abf" :type "string" :timestamp current-time
                      :value_integer nil :value_float nil
                      :producer_timestamp current-time :hash "1234"}]]

      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" {"0" {"e" {"f" [1]}}
                                      "1" {"e" {"f" [2]}}}
                                 "e" "abe"
                                 "f" "abf"
                                 "j" nil}}}
               :timestamp current-time
               :producer_timestamp current-time
               :hash "1234"}]
             (structured-data-seq :v4 test-rows))))))

(deftest test-int-map->vector
  (are [v m] (= v (int-map->vector m))

       ["foo" "bar" "baz"] {1 "bar" 0 "foo" 2 "baz"}
       nil {"1" "bar" "0" "foo" "2" "baz"}
       nil {1 "bar" "0" "foo" "2" "baz"} ;;shouldn't be possible
       ))
