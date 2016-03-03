(ns puppetlabs.puppetdb.pql.parser-test
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [puppetlabs.puppetdb.pql :refer [parse]]))

;; These tests are ordered the same as in the EBNF file, so one can
;; develop the expressions and tests side-by-side.

(deftest test-query
  (are [in expected] (= (parse in :start :query) expected)
    "nodes {}"
    [[:from "nodes"]]

    " nodes {} "
    [[:from "nodes"]])

  (are [in] (insta/failure? (insta/parse parse in :start :query))
    "nodes"
    "{}"
    ""))

(deftest test-from
  (are [in expected] (= (parse in :start :from) expected)
    "nodes {}"
    [:from "nodes"]

    "nodes { a = 1 }"
    [:from
     "nodes"
     [:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "=" [:integer "1"]]]]]]

    "nodes [a, b, c] {}"
    [:from
     "nodes"
     [:extract "a" "b" "c"]]

    "nodes [a, <b>, c] {}"
    [:from
     "nodes"
     [:extract "a" [:groupedfield "b"] "c"]]

    "nodes [a, <b>, <c>] {}"
    [:from
     "nodes"
     [:extract "a" [:groupedfield "b"] [:groupedfield "c"]]]

    "nodes [a, <b>, <c>] { a = 1 }"
    [:from
     "nodes"
     [:extract "a" [:groupedfield "b"] [:groupedfield "c"]]
     [:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "=" [:integer "1"]]]]]]

    "nodes [a, b, c] { a = 1 }"
    [:from
     "nodes"
     [:extract "a" "b" "c"]
     [:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "=" [:integer "1"]]]]]]

    "nodes [a, b, c] { a in facts [a] { b = 2 }}"
    [:from
     "nodes"
     [:extract "a" "b" "c"]
     [:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "in"
         [:from
          "facts"
          [:extract "a"]
          [:expr-or
           [:expr-and
            [:expr-not
             [:condexpression "b" "=" [:integer "2"]]]]]]]]]]]

    "nodes [a, b, c] { [a,b] in facts [a, b] { c = 3 } }"
    [:from
     "nodes"
     [:extract "a" "b" "c"]
     [:expr-or
      [:expr-and
       [:expr-not
        [:condexpression
         [:groupedfieldlist "a" "b"]
         "in"
         [:from
          "facts"
          [:extract "a" "b"]
          [:expr-or
           [:expr-and
            [:expr-not
             [:condexpression "c" "=" [:integer "3"]]]]]]]]]]]

    "facts [value] { [certname,name] in fact_contents [certname, name] { value < 100 }}"
    [:from
     "facts"
     [:extract "value"]
     [:expr-or
      [:expr-and
       [:expr-not
        [:condexpression
         [:groupedfieldlist "certname" "name"]
         "in"
         [:from
          "fact_contents"
          [:extract "certname" "name"]
          [:expr-or
           [:expr-and
            [:expr-not
             [:condexpression "value" "<" [:integer "100"]]]]]]]]]]])

  (are [in] (insta/failure? (insta/parse parse in :start :from))
    "nodes"
    "{}"
    ""))

(deftest test-entity
  (are [in expected] (= (parse in :start :entity) expected)
    "nodes"
    ["nodes"]

    "resources"
    ["resources"]

    "fact_contents"
    ["fact_contents"])

  (are [in] (insta/failure? (insta/parse parse in :start :entity))
    "foobar"
    "hyphen-ated"
    ""))

(deftest test-extract
  (are [in expected] (= (parse in :start :extract) expected)
    "[a,b,c]" [:extract "a" "b" "c"]
    "[ a, b, c ]" [:extract "a" "b" "c"]
    "[ a ]" [:extract "a"]
    "[a]" [:extract "a"]
    "[]" [:extract])

  (are [in] (insta/failure? (insta/parse parse in :start :extract))
    "[a b]"
    "[ab.cd]"
    ""))

(deftest test-extractfields
  (are [in expected] (= (parse in :start :extractfields) expected)
    "a"
    ["a"]

    "a, b"
    ["a" "b"]

    "a,b,c"
    ["a" "b" "c"])

  (are [in] (insta/failure? (insta/parse parse in :start :extractfields))
    "a b"
    "[a,b]"
    ""))

(deftest test-where
  (are [in expected] (= (parse in :start :where) expected)
    "{ a = 1 }"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "=" [:integer "1"]]]]]]

    "{a=1}"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "=" [:integer "1"]]]]]])

  (are [in] (insta/failure? (insta/parse parse in :start :where))
    "[]"
    ""))

(deftest test-expressions
  (are [in expected] (= (parse in :start :expression) expected)
    "a = 1"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "=" [:integer "1"]]]]]]

    "!a = 1"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:not]
        [:expr-not
         [:condexpression "a" "=" [:integer "1"]]]]]]]

    "!(a = 1)"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:not]
        [:expr-not
         [:expr-or
          [:expr-and
           [:expr-not
            [:condexpression "a" "=" [:integer "1"]]]]]]]]]]

    "a = 1 and b = 2"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "a" "=" [:integer "1"]]]
       [:expr-and
        [:expr-not
         [:condexpression "b" "=" [:integer "2"]]]]]]]

    "c = 3 or d = 4 and a = 1"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "c" "=" [:integer "3"]]]]
      [:expr-or
       [:expr-and
        [:expr-not
         [:condexpression "d" "=" [:integer "4"]]]
        [:expr-and
         [:expr-not
          [:condexpression "a" "=" [:integer "1"]]]]]]]]

    "c = 3 or d = 4 and a = 1 or b = 2"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:condexpression "c" "=" [:integer "3"]]]]
      [:expr-or
       [:expr-and
        [:expr-not
         [:condexpression "d" "=" [:integer "4"]]]
        [:expr-and
         [:expr-not
          [:condexpression "a" "=" [:integer "1"]]]]]]
      [:expr-or
       [:expr-and
        [:expr-not
         [:condexpression "b" "=" [:integer "2"]]]]]]]

    "(c = 3 or d = 4) and (a = 1 or b = 2)"
    [[:expr-or
      [:expr-and
       [:expr-not
        [:expr-or
         [:expr-and
          [:expr-not
           [:condexpression "c" "=" [:integer "3"]]]]
         [:expr-or
          [:expr-and
           [:expr-not
            [:condexpression "d" "=" [:integer "4"]]]]]]]
       [:expr-and
        [:expr-not
         [:expr-or
          [:expr-and
           [:expr-not
            [:condexpression "a" "=" [:integer "1"]]]]
          [:expr-or
           [:expr-and
            [:expr-not
             [:condexpression "b" "=" [:integer "2"]]]]]]]]]]])

  (are [in] (insta/failure? (insta/parse parse in :start :expression))
    "foo and 'bar'"
    "1and1"
    "b=1anda=1"
    "facts{} events{}"
    "facts{}events{}"
    "(a = 1) (b = 2)"
    "a=1 b=2"
    ""))

(deftest test-subquery
  (are [in expected] (= (parse in :start :subquery) expected)
    "nodes{}"
    [:subquery "nodes"]

    "nodes { a = 'foo' }"
    [:subquery "nodes" [:expr-or [:expr-and [:expr-not [:condexpression "a" "=" [:sqstring "foo"]]]]]]

    "nodes{a='foo'}"
    [:subquery "nodes" [:expr-or [:expr-and [:expr-not [:condexpression "a" "=" [:sqstring "foo"]]]]]])

  (are [in] (insta/failure? (insta/parse parse in :start :subquery))
    "nodes"
    "nodes[a,b]{}"
    ""))

(deftest test-condexpression
  (testing "condexpression"
    (are [in expected] (= (parse in :start :condexpression) expected)
      "certname = 'foobar'"
      [:condexpression "certname" "=" [:sqstring "foobar"]]

      "certname = 'foobar'"
      [:condexpression "certname" "=" [:sqstring "foobar"]]

      "certname ~ 'foobar'"
      [:condexpression "certname" "~" [:sqstring "foobar"]]

      "path ~> ['a', 'b']"
      [:condexpression "path" "~>" [:groupedregexplist [:sqstring "a"] [:sqstring "b"]]]

      "certname > 4"
      [:condexpression "certname" ">" [:integer "4"]]

      "a in nodes [a] {}"
      [:condexpression "a" "in" [:from "nodes" [:extract "a"]]])

    (are [in] (insta/failure? (insta/parse parse in :start :condexpression))
      "foo >= true"
      "foo <= true"
      "foo < true"
      "foo ~ /bar/"
      "foo = bar"
      "foo != bar"
      "'foo' = bar"
      ""))

  (testing "condexpregexp"
    (are [in expected] (= (parse in :start :condexpregexp) expected)
      "a ~ 'asdf'" ["a" "~" [:sqstring "asdf"]])

    (are [in] (insta/failure? (insta/parse parse in :start :condexpregexp))
      "a ~ /bar/"
      "a ~ 4"
      "a ~ true"
      ""))

  (testing "condexpregexparray"
    (are [in expected] (= (parse in :start :condexpregexparray) expected)
      "a ~> ['asdf']" ["a" "~>" [:groupedregexplist [:sqstring "asdf"]]]
      "a ~> ['asdf', 'foo']" ["a" "~>" [:groupedregexplist [:sqstring "asdf"] [:sqstring "foo"]]])

    (are [in] (insta/failure? (insta/parse parse in :start :condexpregexparray))
      "a ~> 'bar'"
      "a ~> 4"
      "a ~> true"
      ""))

  (testing "condexpinequality"
    (are [in expected] (= (parse in :start :condexpinequality) expected)
      "a >= 4" ["a" ">=" [:integer "4"]])

    (are [in] (insta/failure? (insta/parse parse in :start :condexpinequality))
      "a >= true"
      "a <= true"
      "a < true"
      ""))

  (testing "condexpmatch"
    (are [in expected] (= (parse in :start :condexpmatch) expected)
      "a = 'bar'" ["a" "=" [:sqstring "bar"]]
      "a='bar'" ["a" "=" [:sqstring "bar"]])

    (are [in] (insta/failure? (insta/parse parse in :start :condexpmatch))
      "a = bar"
      "a = /bar/"
      "a != bar"
      ""))

  (testing "condexpin"
    (are [in expected] (= (parse in :start :condexpin) expected)
      "a in nodes [a] {}"
      ["a" "in" [:from "nodes" [:extract "a"]]]

      "[a, b] in nodes[a,b]{}"
      [[:groupedfieldlist "a" "b"] "in" [:from "nodes" [:extract "a" "b"]]]

      "[a] in nodes[a,b]{}"
      [[:groupedfieldlist "a"] "in" [:from "nodes" [:extract "a" "b"]]]

      "[a] in [1]"
      [[:groupedfieldlist "a"] "in" [:groupedliterallist [:integer "1"]]])

    (are [in] (insta/failure? (insta/parse parse in :start :condexpin))
      "a,b in nodes{}[a,b]"
      "[a,b] in [a, b]"
      "")))

(deftest test-condexpnull
  (testing "condexpnull"
    (are [in expected] (= (parse in :start :condexpnull) expected)
      "foo is null"
      [:condexpnull "foo" [:condisnull]]

      "foo is not null"
      [:condexpnull "foo" [:condisnotnull]])

    (are [in] (insta/failure? (insta/parse parse in :start :condexpnull))
      "foo is nil"
      "foois null"
      "fooisnull"
      "foo is"
      ""))

  (testing "condisnull"
    (are [in expected] (= (parse in :start :condisnull) expected)
      "is null" [:condisnull])

    (are [in] (insta/failure? (insta/parse parse in :start :condisnull))
      "is not null"
      "IS NULL"
      ""))

  (testing "condisnotnull"
    (are [in expected] (= (parse in :start :condisnotnull) expected)
      "is not null" [:condisnotnull])

    (are [in] (insta/failure? (insta/parse parse in :start :condisnotnull))
      "is null"
      "IS NOT NULL"
      "")))

(deftest test-conditionalexpressionparts
  (testing "groupedfieldlist"
    (are [in expected] (= (parse in :start :groupedfieldlist) expected)
      "[value,certname]"
      [:groupedfieldlist "value", "certname"]

      "[ value , certname ]"
      [:groupedfieldlist "value", "certname"]

      "[value]"
      [:groupedfieldlist "value"])

    (are [in] (insta/failure? (insta/parse parse in :start :groupedfieldlist))
      "value, certname"
      "(value, certname)"
      "[value, certname,]"
      ""))

  (testing "fieldlist"
    (are [in expected] (= (parse in :start :fieldlist) expected)
      "value, certname" ["value" "certname"]
      "foo,var" ["foo" "var"]
      "foobar" ["foobar"])

    (are [in] (insta/failure? (insta/parse parse in :start :fieldlist))
      "foo:var"
      "foo,bar,"
      "foo bar"
      ""))

  (testing "function"
    (are [in expected] (= (parse in :start :function) expected)
      "count()" [:function "count" [:groupedarglist]]
      "avg(value)" [:function "avg" [:groupedarglist "value"]])

    (are [in] (insta/failure? (insta/parse parse in :start :function))
      "count"
      ""))

  (testing "functionname"
    (are [in expected] (= (parse in :start :functionname) expected)
      "count" ["count"]
      "avg" ["avg"])

    (are [in] (insta/failure? (insta/parse parse in :start :functionname))
      "count dracula"
      ""))

  (testing "groupedarglist"
    (are [in expected] (= (parse in :start :groupedarglist) expected)
      "(certname, value)" [:groupedarglist "certname" "value"]
      "()" [:groupedarglist])

    (are [in] (insta/failure? (insta/parse parse in :start :groupedarglist))
      "('a')"
      ""))

  (testing "arglist"
    (are [in expected] (= (parse in :start :arglist) expected)
      "certname, value" ["certname" "value"]
      "certname" ["certname"])

    (are [in] (insta/failure? (insta/parse parse in :start :arglist))
      "foo bar"
      ""))

  (testing "field"
    (are [in] (= (parse in :start :field) [in])
      "certname"
      "value"
      "field_underscore"
      "latest_report?")

    (are [in] (insta/failure? (insta/parse parse in :start :field))
      "'asdf'"
      "field-hyphen"
      "foo?bar"
      "?"
      ""))

  (testing "condregexp"
    (are [in expected] (= (parse in :start :condregexp) expected)
      "~" ["~"])

    (are [in] (insta/failure? (insta/parse parse in :start :condregexp))
      "="
      ""))

  (testing "condregexparray"
    (is (= (parse "~>" :start :condregexparray) ["~>"]))

    (are [in] (insta/failure? (insta/parse parse in :start :condregexparray))
      "="
      ""))

  (testing "condinequality"
    (are [in] (= (parse in :start :condinequality) [in])
      ">="
      "<="
      ">"
      "<")

    (are [in] (insta/failure? (insta/parse parse in :start :condinequality))
      "="
      "~>"
      "~"
      ""))

  (testing "condmatch"
    (is (= (parse "=" :start :condmatch) ["="]))

    (are [in] (insta/failure? (insta/parse parse in :start :condmatch))
      ">"
      ""))

  (testing "condin"
    (is (= (parse "in" :start :condin) ["in"]))

    (are [in] (insta/failure? (insta/parse parse in :start :condin))
      "ni"
      ""))

  (testing "valueregexp"
    (are [in expected] (= (parse in :start :valueregexp) expected)
      "'asdf'" [[:sqstring "asdf"]]
      "\"asdf\"" [[:dqstring "asdf"]])

    (are [in] (insta/failure? (insta/parse parse in :start :valueregexp))
      "/asdf/"
      "true"
      "/as/df/"
      ""))

  (testing "valueregexparray"
    (are [in expected] (= (parse in :start :valueregexparray) expected)
      "['asdf']" [[:groupedregexplist [:sqstring "asdf"]]]
      "[\"asdf\"]" [[:groupedregexplist [:dqstring "asdf"]]])

    (are [in] (insta/failure? (insta/parse parse in :start :valueregexparray))
      "/asdf/"
      "true"
      "/as/df/"
      ""))

  (testing "valueordered"
    (are [in expected] (= (parse in :start :valueordered) expected)
      "1" [[:integer "1"]]
      "-1" [[:integer "-" "1"]]
      "1.1" [[:real "1" "." "1"]]
      "'2016-02-25'" [[:sqstring "2016-02-25"]])

    (are [in] (insta/failure? (insta/parse parse in :start :valueordered))
      "true"
      "/asdf/"
      ""))

  (testing "valuematch"
    (are [in expected] (= (parse in :start :valuematch) expected)
      "'asdf'" [[:sqstring "asdf"]]
      "1" [[:integer "1"]]
      "1.1" [[:real "1" "." "1"]])

    (are [in] (insta/failure? (insta/parse parse in :start :valuematch))
      "asdf"
      ""))

  (testing "valuein"
    (are [in expected] (= (parse in :start :valuein) expected)
      "nodes{}" [[:from "nodes"]]
      "[1,2]" [[:groupedliterallist [:integer "1"] [:integer "2"]]])

    (are [in] (insta/failure? (insta/parse parse in :start :valuein))
      "asdf"
      ""))

  (testing "groupedregexplist"
    (are [in expected] (= (parse in :start :groupedregexplist) expected)
      "['value','certname']"
      [:groupedregexplist [:sqstring "value"] [:sqstring "certname"]]

      "[ 'value' , 'certname' ]"
      [:groupedregexplist [:sqstring "value"] [:sqstring "certname"]])

    (are [in] (insta/failure? (insta/parse parse in :start :groupedregexplist))
      "'value', 'certname'"
      "('value', 'certname')"
      "['value', 'certname',]"
      ""))

  (testing "regexplist"
    (are [in expected] (= (parse in :start :regexplist) expected)
      "'value', 'certname'" [[:sqstring "value"] [:sqstring "certname"]]
      "'foo','var'" [[:sqstring "foo"], [:sqstring "var"]])

    (are [in] (insta/failure? (insta/parse parse in :start :regexplist))
      "3, 4"
      "foo:var"
      "foo,bar"
      "3,3,"
      "foo bar"
      ""))

  (testing "groupedliterallist"
    (are [in expected] (= (parse in :start :groupedliterallist) expected)
      "['value','certname']"
      [:groupedliterallist [:sqstring "value"] [:sqstring "certname"]]

      "[ 'value' , 'certname' ]"
      [:groupedliterallist [:sqstring "value"] [:sqstring "certname"]])

    (are [in] (insta/failure? (insta/parse parse in :start :groupedliterallist))
      "'value', 'certname'"
      "('value', 'certname')"
      "['value', 'certname',]"
      ""))

  (testing "literallist"
    (are [in expected] (= (parse in :start :literallist) expected)
      "'value', 'certname'" [[:sqstring "value"], [:sqstring "certname"]]
      "'foo','var'" [[:sqstring "foo"], [:sqstring "var"]]
      "3, 4" [[:integer "3"] [:integer "4"]])

    (are [in] (insta/failure? (insta/parse parse in :start :literallist))
      "foo:var"
      "foo,bar"
      "3,3,"
      "foo bar"
      ""))

  (testing "literal"
    (are [in expected] (= (parse in :start :literal) expected)
      "'certname'" [[:sqstring "certname"]]
      "3" [[:integer "3"]]
      "true" [[:boolean [:true]]]
      "3.3" [[:real "3" "." "3"]])

    (are [in] (insta/failure? (insta/parse parse in :start :literal))
      "asdf"
      "{}"
      "")))

(deftest test-booleanoperators
  (testing "and"
    (is (= (parse "and" :start :and) []))

    (are [in] (insta/failure? (insta/parse parse in :start :and))
      "&&"
      ""))

  (testing "or"
    (is (= (parse "or" :start :or) []))

    (are [in] (insta/failure? (insta/parse parse in :start :or))
      "||"
      ""))

  (testing "not"
    (is (= (parse "!" :start :not) [:not]))

    (are [in] (insta/failure? (insta/parse parse in :start :not))
      "not"
      "")))

(deftest test-strings
  (testing "string"
    (are [in expected] (= (parse in :start :string) expected)
      "'asdf'" [[:sqstring "asdf"]]
      "'as\\'df'" [[:sqstring "as\\'df"]]
      "''" [[:sqstring ""]]
      "\"asdf\"" [[:dqstring "asdf"]]
      "\"as\\\"df\"" [[:dqstring "as\\\"df"]]
      "\"\"" [[:dqstring ""]])

    (are [in] (insta/failure? (insta/parse parse in :start :string))
      "'asdf\""
      "\"asdf'"
      "'asd'asdf'"
      "\"asdf\"asdf\""
      ""))

  (testing "stringwithoutdoublequotes"
    (are [in expected] (= (parse in :start :stringwithoutdoublequotes) expected)
      "asdf" ["asdf"]
      "" [""])

    (are [in] (insta/failure? (insta/parse parse in :start :stringwithoutdoublequotes))
      "asdf\"asdf"))

  (testing "stringwithoutsinglequotes"
    (are [in expected] (= (parse in :start :stringwithoutsinglequotes) expected)
      "asdf" ["asdf"]
      "this is a string" ["this is a string"]
      "1" ["1"]
      "" [""])

    (are [in] (insta/failure? (insta/parse parse in :start :stringwithoutsinglequotes))
      "asdf'asdf"))

  (testing "singlequote"
    (is (= (parse "'" :start :singlequote) ["'"]))

    (are [in] (insta/failure? (insta/parse parse in :start :singlequote))
      "`"
      ""))

  (testing "doublequote"
    (is (= (parse "\"" :start :doublequote) ["\""]))

    (are [in] (insta/failure? (insta/parse parse in :start :doublequote))
      "'"
      "")))

(deftest test-paging
  (testing "offset"
    (are [in expected] (= (parse in :start :pagingclause) expected)
         "offset 1" [[:offset [:integer "1"]]]))

  (testing "limit"
    (are [in expected] (= (parse in :start :pagingclause) expected)
         "limit 1" [[:limit [:integer "1"]]]))

  (testing "order by"
    (are [in expected] (= (parse in :start :pagingclause) expected)
         "order by name" [[:orderby [:orderparam "name"]]]
         "order by name, value" [[:orderby [:orderparam "name"] [:orderparam "value"]]]
         "order by name asc, value desc" [[:orderby
                                           [:orderparam "name" "asc"]
                                           [:orderparam "value" "desc"]]]
         "order by name desc, value" [[:orderby
                                       [:orderparam "name" "desc"]
                                       [:orderparam "value"]]])))

(deftest test-parens-grouping
  (is (= (parse ")" :start :rparens) [")"]))

  (are [in] (insta/failure? (insta/parse parse in :start :rparens))
    "["
    "("
    "")

  (is (= (parse "(" :start :lparens) ["("]))

  (are [in] (insta/failure? (insta/parse parse in :start :rparens))
    "]"
    "("
    ""))

(deftest test-brace-grouping
  (is (= (parse "{" :start :lbrace) ["{"]))

  (are [in] (insta/failure? (insta/parse parse in :start :lbrace))
    "["
    "}"
    "")

  (is (= (parse "}" :start :rbrace) ["}"]))

  (are [in] (insta/failure? (insta/parse parse in :start :rbrace))
    "]"
    "{"
    ""))

(deftest test-bracket-grouping
  (is (= (parse "[" :start :lbracket) ["["]))

  (are [in] (insta/failure? (insta/parse parse in :start :lbracket))
    "("
    "]"
    "")

  (is (= (parse "]" :start :rbracket) ["]"]))

  (are [in] (insta/failure? (insta/parse parse in :start :rbracket))
    ")"
    "["
    ""))

(deftest test-booleans
  (testing "boolean"
    (are [in expected] (= (parse in :start :boolean) expected)
      "true" [:boolean [:true]]
      "false" [:boolean [:false]])

    (are [in] (insta/failure? (insta/parse parse in :start :boolean))
      "on"
      "off"
      "1"
      "'true'"
      "'false'"
      "t"
      "")))

(deftest test-numbers
  (testing "integer"
    (are [in expected] (= (parse in :start :integer) expected)
      "1" [:integer "1"]
      "555" [:integer "555"]
      "-1" [:integer "-" "1"])

    (are [in] (insta/failure? (insta/parse parse in :start :integer))
      "- 1"
      "1.1"
      ""))

  (testing "real"
    (are [in expected] (= (parse in :start :real) expected)
      "1.1" [:real "1" "." "1"]
      "1.1E1" [:real "1" "." "1" [:exp "1"]]
      "123.123E123" [:real "123" "." "123" [:exp "123"]]
      "1.1E+1" [:real "1" "." "1" [:exp "+" "1"]]
      "-1.1" [:real "-" "1" "." "1"])

    (are [in] (insta/failure? (insta/parse parse in :start :real))
      "1."
      ".1"
      "."
      ""))

  (testing "exp"
    (are [in expected] (= (parse in :start :exp) expected)
      "e45" [:exp "45"]
      "E45" [:exp "45"]
      "e+45" [:exp "+" "45"]
      "E+45" [:exp "+" "45"]
      "e-45" [:exp "-" "45"]
      "E-45" [:exp "-" "45"])

    (are [in] (insta/failure? (insta/parse parse in :start :exp))
      "E 45"
      ""))

  (testing "digits"
    (are [in] (= (parse in :start :digits) [in])
      "1"
      "123"
      "555")

    (are [in] (insta/failure? (insta/parse parse in :start :digits))
      "1 1"
      "1.1"
      ""))

  (testing "negative"
    (is (= (parse "-" :start :negative) ["-"]))

    (are [in] (insta/failure? (insta/parse parse in :start :negative))
      "+"
      "")))

(deftest test-whitespace
  (are [in] (= (parse in :start :whitespace) [in])
    " "
    "  "
    "\t"
    "\n"
    "\r\n"
    "  \n")

  (are [in] (insta/failure? (insta/parse parse in :start :whitespace))
    "a"
    " b "
    ""))
