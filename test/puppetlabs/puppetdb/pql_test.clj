(ns puppetlabs.puppetdb.pql-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.pql :refer :all]))

(deftest test-pql->ast
  (are [pql ast] (= (first (pql->ast pql))
                    ast)

    ;; Some basic comparisons

    "nodes { a = 'a' }"
    ["from" "nodes"
     ["=" "a" "a"]]

    "nodes { a = 'a' }"
    ["from" "nodes"
     ["=" "a" "a"]]

    "nodes { a = 1 or b = 2 }"
    ["from" "nodes"
     ["or" ["=" "a" 1] ["=" "b" 2]]]

    "nodes { a = 1 and b = 2 }"
    ["from" "nodes"
     ["and" ["=" "a" 1] ["=" "b" 2]]]


    "fact_contents {path = [\"foo\",\"bar\"]}"
    ["from" "fact_contents"
     ["=" "path" ["foo" "bar"]]]

    "fact_contents {path = [\"foo\",\"bar\" ]}"
    ["from" "fact_contents"
     ["=" "path" ["foo" "bar"]]]

    "fact_contents {path = [ \"foo\",\"bar\" ]}"
    ["from" "fact_contents"
     ["=" "path" ["foo" "bar"]]]

    "fact_contents {path = [ \"foo\", \"bar\" ]}"
    ["from" "fact_contents"
     ["=" "path" ["foo" "bar"]]]

    "fact_contents {path = [ \"foo\", \"bar\"]}"
    ["from" "fact_contents"
     ["=" "path" ["foo" "bar"]]]

    "fact_contents {path = [\"foo\", \"bar\" ]}"
    ["from" "fact_contents"
     ["=" "path" ["foo" "bar"]]]

    "fact_contents {path = [\"foo\", 1 ]}"
    ["from" "fact_contents"
     ["=" "path" ["foo" 1]]]

    ;; Not

    "nodes { !(a = 1) }"
    ["from" "nodes"
     ["not" ["=" "a" 1]]]

    "nodes { !a = 1 }"
    ["from" "nodes"
     ["not" ["=" "a" 1]]]

    ;; Null?

    "events { line is null }"
    ["from" "events"
     ["null?" "line" true]]

    "events { line is not null }"
    ["from" "events"
     ["null?" "line" false]]

    ;; Strings & escaping

    "facts { name = 'kernel' }"
    ["from" "facts"
     ["=" "name" "kernel"]]

    "facts { name = 'escapemy\\'quote' }"
    ["from" "facts"
     ["=" "name" "escapemy'quote"]]

    "facts { name = 'carriage\\nreturn' }"
    ["from" "facts"
     ["=" "name" "carriage\\nreturn"]]

    "facts { name = \"escapemy\\\"quote\" }"
    ["from" "facts"
     ["=" "name" "escapemy\"quote"]]

    "facts { name = \"carriage\\nreturn\" }"
    ["from" "facts"
     ["=" "name" "carriage\nreturn"]]

    "facts { name ~ 'escapemy\\'quote' }"
    ["from" "facts"
     ["~" "name" "escapemy'quote"]]

    "fact_contents { path ~> ['networking', 'eth.*'] }"
    ["from" "fact_contents"
     ["~>" "path" ["networking" "eth.*"]]]

    ;; More complex expressions

    "nodes { a = 1 or b = 2 and c = 3 }"
    ["from" "nodes"
     ["or"
      ["=" "a" 1]
      ["and"
       ["=" "b" 2]
       ["=" "c" 3]]]]

    "nodes { a = 1 or b = 2 and c = 3 or d = 4 }"
    ["from" "nodes"
     ["or"
      ["=" "a" 1]
      ["and"
       ["=" "b" 2]
       ["=" "c" 3]]
      ["=" "d" 4]]]

    "nodes { a = 1 or b = 2 and (c = 3 or d = 4) }"
    ["from" "nodes"
     ["or"
      ["=" "a" 1]
      ["and"
       ["=" "b" 2]
       ["or"
        ["=" "c" 3]
        ["=" "d" 4]]]]]

    "nodes { a = 1 or b = 2 and !(c = 3 or d = 4) }"
    ["from" "nodes"
     ["or"
      ["=" "a" 1]
      ["and"
       ["=" "b" 2]
       ["not"
        ["or"
         ["=" "c" 3]
         ["=" "d" 4]]]]]]

    "nodes { a = 1 or b = 2 and (!c = 3 or d = 4) }"
    ["from" "nodes"
     ["or"
      ["=" "a" 1]
      ["and"
       ["=" "b" 2]
       ["or"
        ["not"
         ["=" "c" 3]]
        ["=" "d" 4]]]]]

    ;; Whitespace around parentheses

    "nodes { ( a = 1 or b = 2) }"
    ["from" "nodes"
     ["or"
      ["=" "a" 1]
      ["=" "b" 2]]]

    "nodes { (a = 1 or b = 2 ) }"
    ["from" "nodes"
     ["or"
      ["=" "a" 1]
      ["=" "b" 2]]]

    ;; Extractions

    "nodes[] {}"
    ["from" "nodes"
     ["extract" []]]

    "nodes[a, b, c] {}"
    ["from" "nodes"
     ["extract" ["a" "b" "c"]]]

    "nodes[a, b, c] { a = 1 }"
    ["from" "nodes"
     ["extract" ["a" "b" "c"]
      ["=" "a" 1]]]

    ;; Functions

    "nodes[count(a)] {}"
    ["from" "nodes"
     ["extract" [["function" "count" "a"]]]]

    "nodes[count()] {}"
    ["from" "nodes"
     ["extract" [["function" "count"]]]]

    ;; Subqueries

    "nodes [a, b, c] { a in resources [x] { x = 1 } }"
    ["from" "nodes"
     ["extract" ["a" "b" "c"]
      ["in" "a"
       ["from" "resources"
        ["extract" ["x"]
         ["=" "x" 1]]]]]]

    "nodes[a, b, c] { resources { x = 1 } }"
    ["from" "nodes"
     ["extract" ["a" "b" "c"]
      ["subquery" "resources"
       ["=" "x" 1]]]]

    "nodes[a, b, c] { [a, b] in resources [a, b] { x = 1 }}"
    ["from" "nodes"
     ["extract" ["a" "b" "c"]
      ["in" ["a" "b"]
       ["from" "resources"
        ["extract" ["a" "b"]
         ["=" "x" 1]]]]]]

    "nodes[a, b, c] { [a, b] in [1, 2] }"
    ["from" "nodes"
     ["extract" ["a" "b" "c"]
      ["in" ["a" "b"]
       ["array" [1 2]]]]]

    "nodes[a,b,c] { resources { x = 1 } }"
    ["from" "nodes"
     ["extract" ["a" "b" "c"]
      ["subquery" "resources"
       ["=" "x" 1]]]]

    "facts[value] { [certname,name] in fact_contents[certname,name] { value < 100 }}"
    ["from" "facts"
     ["extract" ["value"]
      ["in" ["certname" "name"]
       ["from" "fact_contents"
        ["extract" ["certname" "name"] ["<" "value" 100]]]]]]

    "facts[value] { fact_contents { value < 100 } }"
    ["from" "facts"
     ["extract" ["value"]
      ["subquery" "fact_contents"
       ["<" "value" 100]]]]

    ;; Modifiers
    "facts[name, count()] { group by name }"
    ["from" "facts"
     ["extract"
      ["name" ["function" "count"]]
      ["group_by" "name"]]]

    "facts[name, count(value)] { certname ~ 'web.*' group by name }"
    ["from" "facts"
     ["extract" ["name" ["function" "count" "value"]]
      ["~" "certname" "web.*"]
      ["group_by" "name"]]]

    "events[count(), status, certname] { certname ~ 'web.*' group by status, certname }"
    ["from" "events"
     ["extract", [["function" "count"] "status" "certname"],
      ["~" "certname" "web.*"]
      ["group_by" "status" "certname"]]]


    ;; Paging
    "reports{limit 1}"
    ["from" "reports"
     ["limit" 1]]

    "reports {limit 1 offset 1}"
    ["from" "reports"
     ["limit" 1] ["offset" 1]]

    "reports {certname = 'foo' limit 1 offset 1}"
    ["from" "reports"
     ["=" "certname" "foo"]
     ["limit" 1] ["offset" 1]]

    "reports[certname, receive_time]{certname = 'foo' limit 1 offset 1}"
    ["from" "reports"
     ["extract" ["certname" "receive_time"] ["=" "certname" "foo"]]
     ["limit" 1] ["offset" 1]]

    "reports[certname]{certname = 'foo' limit 10 order by certname}"
    ["from" "reports"
     ["extract" ["certname"] ["=" "certname" "foo"]]
     ["limit" 10] ["order_by" ["certname"]]]

    "reports[certname]{certname = 'foo' limit 10 order by certname desc}"
    ["from" "reports"
     ["extract" ["certname"] ["=" "certname" "foo"]]
     ["limit" 10] ["order_by" [["certname" "desc"]]]]

    "reports[certname]{certname = 'foo' limit 10 order by certname desc, receive_time asc}"
    ["from" "reports"
     ["extract" ["certname"] ["=" "certname" "foo"]]
     ["limit" 10] ["order_by" [["certname" "desc"] ["receive_time" "asc"]]]]

    "reports[certname]{certname = 'foo' limit 10 order by certname, receive_time desc}"
    ["from" "reports"
     ["extract" ["certname"] ["=" "certname" "foo"]]
     ["limit" 10] ["order_by" ["certname" ["receive_time" "desc"]]]]

    "reports[certname]{certname = 'foo' order by certname desc, receive_time asc limit 10}"
    ["from" "reports"
     ["extract" ["certname"] ["=" "certname" "foo"]]
     ["order_by" [["certname" "desc"] ["receive_time" "asc"]]] ["limit" 10]]

    ;;Inequality on dates
    "reports{receive_time > '2016-02-07T08:45:42.170687300Z'}"
    ["from" "reports"
     [">" "receive_time" "2016-02-07T08:45:42.170687300Z"]]

    "reports{receive_time >= '2016-02-07T08:45:42.170687300Z'}"
    ["from" "reports"
     [">=" "receive_time" "2016-02-07T08:45:42.170687300Z"]]

    "reports{receive_time <= '2016-02-07T08:45:42.170687300Z'}"
    ["from" "reports"
     ["<=" "receive_time" "2016-02-07T08:45:42.170687300Z"]]

    "reports{receive_time < '2016-02-07T08:45:42.170687300Z'}"
    ["from" "reports"
     ["<" "receive_time" "2016-02-07T08:45:42.170687300Z"]]))
