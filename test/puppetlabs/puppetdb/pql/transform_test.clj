(ns puppetlabs.puppetdb.pql.transform-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.pql :refer [transform]]
   [puppetlabs.puppetdb.pql.transform
    :refer [transform-boolean
            transform-condexpnull
            transform-condexpression
            transform-dqstring
            transform-exp
            transform-expr-and
            transform-expr-not
            transform-expr-or
            transform-extract
            transform-from
            transform-function
            transform-groupby
            transform-groupedlist
            transform-groupedliterallist
            transform-integer
            transform-real
            transform-sqstring
            transform-subquery]]))

(deftest test-from
  (testing "function"
    (are [in expected] (= (apply transform-from in) expected)
      ["nodes"]
      ["from" "nodes"]

      ["nodes" ["=" "a" 1]]
      ["from" "nodes"
       ["=" "a" 1]]

      ["nodes" ["extract" ["a" "b" "c"]]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]]]

      ["nodes" ["extract" ["a" "b" "c"]] ["=" "a" 1]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]
        ["=" "a" 1]]]

      ["nodes" ["extract" ["a" "b" "c"]] ["=" "a" 1] ["group_by" "a"]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]
        ["=" "a" 1]
        ["group_by" "a"]]]

      ["nodes" ["extract" ["a" "b" "c"]] ["=" "a" 1] ["limit" 1]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]
        ["=" "a" 1]]
        ["limit" 1]]

      ["nodes" ["extract" ["a" "b" "c"]] ["=" "a" 1] ["order_by" ["certname"]]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]
        ["=" "a" 1]]
       ["order_by" ["certname"]]]

      ["nodes" ["extract" ["a" "b" "c"]] ["=" "a" 1] ["order_by" [["certname" "desc"]]]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]
        ["=" "a" 1]]
       ["order_by" [["certname" "desc"]]]]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:from "nodes"]
      ["from" "nodes"]

      [:from "nodes"
       [:expr-or
        [:expr-and
         [:expr-not
          [:condexpression "a" "=" [:integer "1"]]]]]]
      ["from" "nodes"
       ["=" "a" 1]]

      [:condexpression [:field "certname"] "!=" [:integer "4"]]
      ["not" ["=" "certname" 4]]

      [:condexpression [:field "certname"] "!~" [:integer "4"]]
      ["not" ["~" "certname" 4]]

      [:from "nodes"
       [:extract "a" "b" "c"]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]]]

      [:from
       "nodes"
       [:extract "a" "b" "c"]
       [:expr-or
        [:expr-and
         [:expr-not
          [:condexpression
           "a"
           "in"
           [:from
            "facts"
            [:extract "a"]
            [:expr-or
             [:expr-and
              [:expr-not [:condexpression "b" "=" [:integer "2"]]]]]]]]]]]
      ["from" "nodes"
       ["extract" ["a" "b" "c"]
        ["in" "a"
         ["from" "facts"
          ["extract" ["a"]
           ["=" "b" 2]]]]]])))

(deftest test-subquery
  (testing "function"
    (are [in expected] (= (apply transform-subquery in) expected)
      ["nodes"]
      ["subquery" "nodes"]

      ["nodes" ["=" "a" 1]]
      ["subquery" "nodes"
       ["=" "a" 1]]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:subquery "nodes"]
      ["subquery" "nodes"]

      [:subquery "nodes"
       [:expr-or
        [:expr-and
         [:expr-not
          [:condexpression "a" "=" [:integer "1"]]]]]]
      ["subquery" "nodes"
       ["=" "a" 1]]

      [:subquery
       "nodes"
       [:expr-or
        [:expr-and
         [:expr-not
          [:condexpression
           "a"
           "in"
           [:from
            "facts"
            [:extract "a"]
            [:expr-or
             [:expr-and
              [:expr-not [:condexpression "b" "=" [:integer "2"]]]]]]]]]]]
      ["subquery" "nodes"
       ["in" "a"
        ["from" "facts"
         ["extract" ["a"]
          ["=" "b" 2]]]]])))

(deftest test-extract
  (testing "function"
    (are [in expected] (= (apply transform-extract in) expected)
      ["a" "b" "c"]
      ["extract" ["a" "b" "c"]]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:extract "a" "b" "c"]
      ["extract" ["a" "b" "c"]])))

(deftest test-expr-or
  (testing "function"
    (are [in expected] (= (apply transform-expr-or in) expected)
      [["=" "a" 1]]
      ["=" "a" 1]

      [["=" "a" 1] ["=" "b" 2]]
      ["or"
       ["=" "a" 1]
       ["=" "b" 2]]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:expr-or
       [:expr-and
        [:expr-not
         [:condexpression
          "a" "=" [:integer "1"]]]]]
      ["=" "a" 1]

      [:expr-or
       [:expr-and
        [:expr-not
         [:condexpression
          "a" "=" [:integer "1"]]]]
       [:expr-and
        [:expr-not
         [:condexpression
          "b" "=" [:integer "2"]]]]]
      ["or"
       ["=" "a" 1]
       ["=" "b" 2]])))

(deftest test-expr-and
  (testing "function"
    (are [in expected] (= (apply transform-expr-and in) expected)
      [["=" "a" 1]]
      ["=" "a" 1]

      [["=" "a" 1] ["=" "b" 2]]
      ["and"
       ["=" "a" 1]
       ["=" "b" 2]]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:expr-and
       [:expr-not
        [:condexpression
         "a" "=" [:integer "1"]]]]
      ["=" "a" 1]

      [:expr-and
       [:expr-not
        [:condexpression
         "a" "=" [:integer "1"]]]
       [:expr-not
        [:condexpression
         "b" "=" [:integer "2"]]]]
      ["and"
       ["=" "a" 1]
       ["=" "b" 2]])))

(deftest test-expr-not
  (testing "function"
    (are [in expected] (= (apply transform-expr-not in) expected)
      [["=" "a" 1]]
      ["=" "a" 1]

      [[:not] ["=" "a" 1]]
      ["not" ["=" "a" 1]]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:expr-not
       ["=" "a" 1]]
      ["=" "a" 1]

      [:expr-not
       [:not]
       [:condexpression "a" "=" [:integer 1]]]
      ["not" ["=" "a" 1]])))

(deftest test-function
  (testing "function"
    (are [in expected] (= (apply transform-function in) expected)
      ["count" ["a" "b"]]
      ["function" "count" "a" "b"]

      ["count" []]
      ["function" "count"]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:function "count" ["a" "b"]]
      ["function" "count" "a" "b"])))

(deftest test-condexpression
  (testing "function"
    (are [in expected] (= (apply transform-condexpression in) expected)
      ["a" "~" "foo"]
      ["~" "a" "foo"]

      ["a" "==" 1]
      ["==" "a" 1]

      ["foo" "null?" true]
      ["null?" "foo" true]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:condexpression "a" "==" [:integer "1"]]
      ["==" "a" 1]

      [:condexpression "a" "~" [:sqstring "foo"]]
      ["~" "a" "foo"]

      [:condexpression "foo" "null?" [:boolean [:true]]]
      ["null?" "foo" true])))

(deftest test-condexpnull
  (testing "function"
    (are [in expected] (= (apply transform-condexpnull in) expected)
      ["foo" [:condisnull]]
      ["null?" "foo" true]

      ["foo" [:condisnotnull]]
      ["null?" "foo" false]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:condexpnull "foo" [:condisnull]]
      ["null?" "foo" true]

      [:condexpnull "foo" [:condisnotnull]]
      ["null?" "foo" false])))

(deftest test-groupedlist
  (testing "function"
    (are [in expected] (= (apply transform-groupedlist in) expected)
      []
      []

      ["a"]
      ["a"]

      ["a" "b"]
      ["a" "b"]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:groupedarglist "a" "b"]
      ["a" "b"]

      [:groupedarglist "a"]
      ["a"]

      [:groupedfieldlist "a" "b"]
      ["a" "b"]

      [:groupedfieldlist "a"]
      ["a"]

      [:groupedregexplist [:sqstring "a"] [:sqstring "b"]]
      ["a" "b"]

      [:groupedregexplist [:sqstring "a"]]
      ["a"])))

(deftest test-groupedliterallist
  (testing "function"
    (are [in expected] (= (apply transform-groupedliterallist in) expected)
      [[:sqstring "a"] [:sqstring "b"]]
      ["array" [[:sqstring "a"] [:sqstring "b"]]]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:groupedliterallist [:sqstring "a"] [:sqstring "b"]]
      ["array" ["a" "b"]]

      [:groupedliterallist [:integer 1] [:integer 2]]
      ["array" [1 2]])))

(deftest test-sqstring
  (testing "function"
    (are [in expected] (= (apply transform-sqstring in) expected)
      ["foo"] "foo"
      ["foo\\'foo"] "foo'foo"
      ["foo\\nfoo"] "foo\\nfoo"))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:sqstring "asdf"] "asdf"
      [:sqstring "foo\\'foo"] "foo'foo")))

(deftest test-dqstring
  (testing "function"
    (are [in expected] (= (apply transform-dqstring in) expected)
      ["foo"] "foo"
      ["foo\\\"foo"] "foo\"foo"
      ["foo\\nfoo"] "foo\nfoo"))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:dqstring "asdf"] "asdf"
      [:dqstring "foo\\\"foo"] "foo\"foo")))

(deftest test-boolean
  (testing "function"
    (are [in expected] (= (apply transform-boolean in) expected)
      [[:true]] true
      [[:false]] false))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:boolean [:true]] true
      [:boolean [:false]] false)))

(deftest test-integer
  (testing "function"
    (are [in expected] (= (apply transform-integer in) expected)
      ["3"] 3
      ["-" "3"] -3))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:integer "3"] 3
      [:integer "-" "3"] -3)))

(deftest test-real
  (testing "function real"
    (are [in expected] (= (apply transform-real in) expected)
      ["1" "." "1"] 1.1
      ["1" "." "1" "E123"] 1.1E123
      ["-" "1" "." "1"] -1.1
      ["-" "1" "." "1" "E-123"] -1.1E-123))

  (testing "function exp"
    (are [in expected] (= (apply transform-exp in) expected)
      ["123"] "E123"
      ["-" "123"] "E-123"
      ["+" "123"] "E+123"))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:real "1" "." "1"] 1.1
      [:real "1" "." "1" [:exp "45"]] 1.1E45
      [:real "1" "." "1" [:exp "-" "45"]] 1.1E-45
      [:real "1" "." "1" [:exp "+" "45"]] 1.1E45
      [:real "-" "1" "." "1"] -1.1)))

(deftest test-groupby
  (testing "function"
    (are [in expected] (= (apply transform-groupby in) expected)
      ["a"] ["group_by" "a"]
      ["a" "b"] ["group_by" "a" "b"]))

  (testing "transform"
    (are [in expected] (= (transform in) expected)
      [:groupby "a"] ["group_by" "a"])))
