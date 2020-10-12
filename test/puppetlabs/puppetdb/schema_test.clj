(ns puppetlabs.puppetdb.schema-test
  (:import [org.joda.time Minutes Days Seconds Period])
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.schema :refer :all]
            [puppetlabs.puppetdb.time :as time]
            [schema.core :as s]
            [schema.coerce :as sc]))

(deftest defaulted-maybe-test
  (let [defaulted-schema {:foo (defaulted-maybe Number 10)}]
    (is (= {:foo 10}
           (s/validate defaulted-schema
                       {:foo 10})))
    (is (= {:foo nil}
           (s/validate defaulted-schema
                       {:foo nil})))

    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Value does not match schema: \{:foo missing-required-key\}"
                          (s/validate {:foo (defaulted-maybe Number 10)}
                                      {})))

    (is (= {:foo 10}
           (->> {:foo nil}
                (s/validate defaulted-schema)
                (defaulted-data defaulted-schema)
                (s/validate defaulted-schema))))))

(deftest defaulted-maybe?-test
  (is (true? (defaulted-maybe? (defaulted-maybe Number 10))))
  (is (false? (defaulted-maybe? (s/maybe Number)))))

(deftest defaulted-maybe-keys-test
  (is (= [:foo]
         (defaulted-maybe-keys
           {:foo (defaulted-maybe Number 1)
            :bar (s/maybe Number)
            :baz String}))))

(deftest schema-key->data-key-test
  (are [x y] (= x (schema-key->data-key y))
       :foo (s/optional-key :foo)
       :foo :foo
       :foo (s/required-key :foo)))

(deftest blocklist->vector-test
  (is (= ["baz" "bar"] (blocklist->vector "baz, bar")))
  (is (= ["foo" "bar"] (blocklist->vector ["foo" "bar"])))
  (is (thrown-with-msg? java.lang.Exception
                        #"Invalid facts blocklist format"
                        (blocklist->vector ["foo" :bar]))))

(deftest unknown-keys-test
  (let [schema {(s/required-key :foo) Number
                (s/optional-key :bar) Number
                :baz String}]
    (is (= '(:foo-1)
           (unknown-keys
            schema
            {:foo 1
             :foo-1 2
             :bar 3
             :baz 5})))
    (is (= '(:foo-1)
           (unknown-keys
            schema
            {:foo 1
             :foo-1 2})))
    (is (empty?
          (unknown-keys
            schema
            {})))
    (is (= '(:foo-1 :foo-2)
           (unknown-keys
            schema
            {:foo-1 "foo"
             :foo-2 "baz"})))))

(deftest strip-unknown-keys-test
  (let [schema {(s/required-key :foo) Number
                (s/optional-key :bar) Number
                :baz String}]
    (is (= {:foo 1
            :bar 3
            :baz 5}
           (strip-unknown-keys
            schema
            {:foo 1
             :foo-1 2
             :bar 3
             :baz 5})))
    (is (= {:foo 1}
           (strip-unknown-keys
            schema
            {:foo 1
             :foo-1 2})))
    (is (= {}
           (strip-unknown-keys
            schema
            {})))
    (is (= {}
           (strip-unknown-keys
            schema
            {:foo-1 "foo"
             :foo-2 "baz"})))))

(deftest defaulted-data-test
  (let [schema {:foo Number
                (s/optional-key :foo-1) (defaulted-maybe Number 1)
                (s/required-key :bar) Number
                (s/optional-key :baz-2) (defaulted-maybe String "bar-2")
                (s/optional-key :baz) (defaulted-maybe String "baz")}]

    (testing "all defaulted"
      (is (= {:foo-1 1
              :baz-2 "bar-2"
              :baz "baz"}
             (defaulted-data schema {}))))

    (testing "some defaulted"
      (is (= {:foo 20
              :foo-1 10000
              :bar 2
              :baz-2 "bar-2"
              :baz "baz"}
             (defaulted-data schema {:foo 20
                                     :foo-1 10000
                                     :bar 2}))))

    (testing "none defaulted"
      (is (= {:foo 20
              :foo-1 10000
              :bar 2
              :baz-2 "really baz 2"
              :baz "not baz"}
             (defaulted-data schema  {:foo 20
                                      :foo-1 10000
                                      :bar 2
                                      :baz-2 "really baz 2"
                                      :baz "not baz"}))))))

(deftest test-strip-unknown-keys
  (let [schema {:foo Number
                (s/optional-key :bar) (defaulted-maybe Number 1)
                (s/required-key :baz) Number}]
    (testing "strip all keys"
      (is (empty? (strip-unknown-keys schema {:not-foo 1
                                              :not-bar 2
                                              :not-baz 3}))))
    (testing "strip some keys"
      (is (is (= {:foo 1
                  :bar 3
                  :baz 5}
                 (strip-unknown-keys schema {:foo 1
                                             :not-foo 2
                                             :bar 3
                                             :not-bar 4
                                             :baz 5
                                             :not-baz 6})))))
    (testing "strip no keys"
      (is (is (= {:foo 1
                  :bar 3
                  :baz 5}
                 (strip-unknown-keys schema {:foo 1
                                             :bar 3
                                             :baz 5})))))))

(deftest schema-type-construction
  (are [expected target-schema source-schema value]
    (= expected ((sc/coercer target-schema conversion-fns) value))

    (time/minutes 10) Minutes String "10"
    (time/minutes 10) Minutes Number 10

    (time/seconds 10) Seconds String "10"
    (time/seconds 10) Seconds Number 10

    (time/days 10) Days String "10"
    (time/days 10) Days Number 10

    (time/parse-period "10d") Period String "10d"

    :foo s/Keyword s/Keyword :foo
    10 s/Int s/Int 10

    true Boolean String "true"
    false Boolean String "false"
    true Boolean String "TRUE"
    false Boolean String "FALSE"
    true Boolean String "True"
    false Boolean String "False"
    false Boolean String "really false"))

(deftest schema-conversion
  (testing "conversion of days/minutes/seconds"
    (let [schema {:foo Days
                  :bar Minutes
                  :baz Seconds}

          result {:foo (time/days 10)
                  :bar (time/minutes 20)
                  :baz (time/seconds 30)}]
      (is (= result
             (convert-to-schema schema {:foo 10
                                        :bar 20
                                        :baz 30})))
      (is (= result
             (convert-to-schema schema {:foo "10"
                                        :bar "20"
                                        :baz "30"})))))
  (testing "conversion with time periods"
    (let [schema {:foo Period
                  :bar Minutes
                  :baz Period}
          result {:foo (time/parse-period "10d")
                  :bar (time/minutes 20)
                  :baz (time/parse-period "30s")}]
      (is (= result
             (convert-to-schema schema {:foo "10d"
                                        :bar "20"
                                        :baz "30s"})))
      (is (= result
             (convert-to-schema schema {:foo "10d"
                                        :bar 20
                                        :baz "30s"})))))

  (testing "partial conversion"
    (let [schema {:foo String
                  :bar Number
                  :baz Period}
          result {:foo "foo"
                  :bar 10
                  :baz (time/parse-period "30s")}]
      (is (= result
             (convert-to-schema schema {:foo "foo"
                                        :bar 10
                                        :baz "30s"})))))

  (testing "conversion with an optional and a maybe"
    (let [schema {:foo String
                  :bar Number
                  (s/optional-key :baz) (s/maybe Period)
                  (s/optional-key :bog) (s/maybe s/Int)
                  (s/optional-key :taf) (s/maybe s/Keyword)
                  (s/optional-key :baz-nil) (s/maybe Period)
                  (s/optional-key :bog-nil) (s/maybe s/Int)
                  (s/optional-key :taf-nil) (s/maybe s/Keyword)}
          result {:foo "foo"
                  :bar 10
                  :baz (time/parse-period "30s")
                  :bog 1000
                  :taf :foo
                  :baz-nil nil
                  :bog-nil nil
                  :taf-nil nil}]
      (is (= result
             (convert-to-schema schema {:foo "foo"
                                        :bar 10
                                        :baz "30s"
                                        :bog 1000
                                        :taf :foo
                                        :baz-nil nil
                                        :bog-nil nil
                                        :taf-nil nil})))))

  (testing "conversion from double to integer"
    (let [schema {:foo Long}]
      (is (= {:foo 10}
             (convert-to-schema schema {:foo 10.0})))))

  (testing "blocklisted facts conversion"
    (let [schema {:facts-blocklist clojure.lang.PersistentVector}]
      (is (= {:facts-blocklist ["fact1" "fact2"]}
             (convert-to-schema schema {:facts-blocklist "fact1, fact2"}))))))
