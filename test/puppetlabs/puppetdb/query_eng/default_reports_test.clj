(ns puppetlabs.puppetdb.query-eng.default-reports-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.query-eng.engine
    :refer [inventory-query reports-query report-logs-query]]
   [puppetlabs.puppetdb.query-eng.default-reports :as t]))

;; Q: no page-order-opts -- needed?

(deftest test-mentions-report-type?
  (is (= false (t/mentions-report-type? [])))

  (doseq [op ["=" ">" "<" ">=" "<=" "~" "~>"]]
    (testing (str "binary operator " op)
      (is (= false (t/mentions-report-type? [op "x" "y"])))
      (is (= true (t/mentions-report-type? [op "type" "y"])))))

  (testing (str "unary operator null?")
    (is (= false (t/mentions-report-type? ["null?" "x" true])))
    (is (= true (t/mentions-report-type? ["null?" "type" true]))))

  (testing (str "unary operator not")
    (is (= false (t/mentions-report-type? ["not" ["=" "x" "y"]])))
    (is (= true (t/mentions-report-type? ["not" ["=" "type" "y"]])))
    (is (= true (t/mentions-report-type? ["not" ["null?" "type" true]])))

    ;; the subquery will have a filter added if necessary by a different function
    (is (= false (t/mentions-report-type? ["not"
                                           ["in" "x"
                                            ["from" "reports"
                                             ["extract" "type" ["=" "type" "plan"]]]]]))))

  (is (= false (t/mentions-report-type? ["in" "x" ["array" "y" "z"]])))
  (is (= true (t/mentions-report-type? ["in" "type" ["array" "x" "y"]])))


  ;; subqueries - dead end end for this check
  (doseq [[expr kind] [[["=" "something" "report"] "not mentioning type"]
                       [["=" "type" "agent"] "mentioning type"]]]
    (testing (str "subqueries " kind)

      (is (= false (t/mentions-report-type?
                    ["in" "x"
                     ["from" "nodes"
                      ["extract" "y" expr]]])))
      (is (= false (t/mentions-report-type?
                    ["in" ["x"]
                     ["from" "nodes"
                      ["extract" ["y"] expr]]])))

      (is (= false (t/mentions-report-type?
                    ["in" "x"
                     ["extract" "y"
                      ["select_reports" expr]]])))
      (is (= false (t/mentions-report-type?
                    ["in" ["x"]
                     ["extract" ["y"]
                      ["select_reports" expr]]])))))

  (doseq [op ["and" "or"]]
    (testing (str "logical operator " op)
      (is (= false (t/mentions-report-type? [op ["=" "x" "y"]])))
      (is (= false (t/mentions-report-type? [op ["=" "w" "x"] ["=" "y" "z"]])))
      (is (= true (t/mentions-report-type? [op ["=" "type" "y"]])))
      (is (= true (t/mentions-report-type? [op ["=" "w" "x"] ["=" "type" "z"]]))))))

(deftest test-maybe-add-agent-report-filter-to-subqueries
  (testing "no change to non-subquery operators"
    (doseq [op ["=" ">" "<" ">=" "<=" "~" "~>"]]
      (is (= [op "type" "y"] (t/maybe-add-agent-report-filter-to-subqueries [op "type" "y"]))))

      (is (= ["null?" "x" true] (t/maybe-add-agent-report-filter-to-subqueries ["null?" "x" true])))

      (is (= ["not" ["=" "x" "y"]] (t/maybe-add-agent-report-filter-to-subqueries ["not" ["=" "x" "y"]])))

    (doseq [op ["and" "or"]]
      (is (= [op ["=" "foo" "bar"] ["=" "bar" "baz"]] (t/maybe-add-agent-report-filter-to-subqueries [op ["=" "foo" "bar"] ["=" "bar" "baz"]]))))

      (is (= ["in" "foo" ["array" "foo" "bar"]] (t/maybe-add-agent-report-filter-to-subqueries ["in" "foo" ["array" "foo" "bar"]])))))

(deftest test-random-bits

  (is (= false (t/qrec-involving-reports? inventory-query)))
  (is (= true (t/qrec-involving-reports? reports-query)))
  (is (= true (t/qrec-involving-reports? report-logs-query)))


  (is (= [inventory-query []]
         (t/maybe-add-agent-report-filter inventory-query [])))

  (is (= [reports-query ["=" "type" "agent"]]
         (t/maybe-add-agent-report-filter reports-query [])))

  (is (= [reports-query ["=" "type" "plan"]]
         (t/maybe-add-agent-report-filter reports-query
                                          ["=" "type" "plan"]))))
