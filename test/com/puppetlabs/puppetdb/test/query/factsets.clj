(ns com.puppetlabs.puppetdb.test.query.factsets
  (:require [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.query.factsets :refer :all]))

(deftest test-str->num
  (are [n s] (= n (str->num s))

       10 "10"
       123 "123"
       0 "0"
       nil "foo"
       nil "123foo"))

(deftest test-int-map->vector
  (are [v m] (= v (int-map->vector m))

       ["foo" "bar" "baz"] {1 "bar" 0 "foo" 2 "baz"}
       nil {"1" "bar" "0" "foo" "2" "baz"}
       nil {1 "bar" "0" "foo" "2" "baz"} ;;shouldn't be possible
       ))

(deftest test-unescape-string
  (are [unescaped s] (= unescaped (unescape-string s))

       "foo" "\"foo\""
       "foo" "foo"
       "123" "123"
       "123foo" "\"123foo\""))

(deftest test-unencode-path-segment
  (are [path-segment s] (= path-segment (unencode-path-segment s))

       "foo" "\"foo\""
       "\"foo\"" "\"\"foo\"\""
       "foo" "foo"

       "123" "\"123\""
       "123foo" "\"123foo\""
       1 "1"
       123 "123"))

(deftest test-collapsing-factsets
  (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc"}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~d" :value "abd"}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe"}
                   {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf"}]]
    (is (= [{:certname "foo.com"
             :environment "DEV"
             :facts {"a" {"b" {"c" "abc"
                               "d" "abd"
                               "e" "abe"
                               "f" "abf"}}}}]
           (collapsed-fact-seq test-rows))))

  (testing "laziness of the collapsing fns"
    (let [ten-billion 10000000000]
      (is (= 10
             (count
              (take 10
                    (collapsed-fact-seq
                     (mapcat (fn [certname]
                               [{:certname certname :environment "DEV" :path "a#~b#~c" :value "abc"}
                                {:certname certname :environment "DEV" :path "a#~b#~d" :value "abd"}
                                {:certname certname :environment "DEV" :path "a#~b#~e" :value "abe"}
                                {:certname certname :environment "DEV" :path "a#~b#~f" :value "abf"}])
                             (map #(str "foo" % ".com") (range 0 ten-billion))))))))))

  (testing "map with a nested vector"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~0" :value "abd"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~1" :value "abdabd"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf"}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" ["abd" "abdabd"]
                                 "e" "abe"
                                 "f" "abf"}}}}]
             (collapsed-fact-seq test-rows)))))
  (testing "map with a nested vector of maps"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~0#~e#~f#~0" :value "ghi"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~1#~e#~f#~0" :value "ghighi"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf"}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" [{"e" {"f" ["ghi"]}}
                                      {"e" {"f" ["ghighi"]}}]
                                 "e" "abe"
                                 "f" "abf"}}}}]
             (collapsed-fact-seq test-rows)))))

  (testing "map stringified integer keys"
    (let [test-rows [{:certname "foo.com" :environment "DEV" :path "a#~b#~c" :value "abc"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"0\"#~e#~f#~0" :value "ghi"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~d#~\"1\"#~e#~f#~0" :value "ghighi"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~e" :value "abe"}
                     {:certname "foo.com" :environment "DEV" :path "a#~b#~f" :value "abf"}]]
      (is (= [{:certname "foo.com"
               :environment "DEV"
               :facts {"a" {"b" {"c" "abc"
                                 "d" {"0" {"e" {"f" ["ghi"]}}
                                      "1" {"e" {"f" ["ghighi"]}}}
                                 "e" "abe"
                                 "f" "abf"}}}}]
             (collapsed-fact-seq test-rows))))))
