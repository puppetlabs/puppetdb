(ns puppetlabs.puppetdb.facts-test
  (:require [puppetlabs.puppetdb.facts :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [schema.core :as s]))

(deftest test-str->num
  (are [n s] (= n (str->num s))

       10 "10"
       123 "123"
       0 "0"
       nil "foo"
       nil "123foo"))

(deftest test-unencode-path-segment
  (are [path-segment s] (= path-segment (unencode-path-segment s))
       "\"foo\"" "\"foo\""
       "\"\"foo\"\"" "\"\"foo\"\""
       "foo" "foo"
       "123" "\"123\""
       "\"123foo\"" "\"123foo\""
       1 "1"
       123 "123"))

(deftest test-factpath-to-string-and-reverse
  (let [data
        [["foo#~bar"      ["foo" "bar"]]
         ["foo"           ["foo"]]
         ["foo#~bar#~baz" ["foo" "bar" "baz"]]
         ["foo\\#\\~baz"  ["foo#~baz"]]
         ["foo#~0"        ["foo" 0]]
         ["foo#~\"123\""  ["foo" "123"]]]]
    (doseq [[r l] data]
      (is (= (string-to-factpath r) l))
      (is (= r (factpath-to-string l))))))

(deftest test-fact-normalization
  (let [received-str "2017-03-14T14:18:32.635Z"
        producer-ts "2017-03-14T14:18:32.635Z"
        example-wire-facts {:certname "foo.com"
                            :environment "test"
                            :producer_timestamp producer-ts
                            :producer "puppetserver"
                            :values {:foo "1"
                                     :bar "2"}
                            :package_inventory [["openssl" "1.1.0e-1" "apt"]]}]
    (are [version facts] (s/validate facts-schema (normalize-facts version received-str facts))
      5 example-wire-facts
      5 (dissoc example-wire-facts :package_inventory)
      5 (assoc example-wire-facts :values {"foo bar" "1"})
      4 (dissoc example-wire-facts :package_inventory :producer)
      3 (-> example-wire-facts
            (dissoc :package_inventory :producer :producer_timestamp :certname)
            (assoc :producer-timestamp producer-ts :name "foo.com"))
      2 (-> example-wire-facts
            (dissoc :package_inventory :producer :producer_timestamp :certname)
            (assoc :name "foo.com")))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not match schema"
                          (->> (assoc example-wire-facts :package_inventory [["openssl" "1.1.0e-1"]])
                               (normalize-facts 5 received-str)
                               (s/validate facts-schema))))))
