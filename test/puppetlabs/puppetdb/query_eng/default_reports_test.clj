(ns puppetlabs.puppetdb.query-eng.default-reports-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.query-eng.engine
    :refer [inventory-query nodes-query report-logs-query]]
   [puppetlabs.puppetdb.query-eng.default-reports :as t]))

;; Q: no page-order-opts -- needed?

(deftest test-mentions-report-type?
  (is (= false (t/mentions-report-type? [])))

  (doseq [op ["=" ">" "<" ">=" "<=" "~" "~>"]]
    (testing (str op "binary operator")
      (is (= false (t/mentions-report-type? ["=" "x" "y"])))
      (is (= true (t/mentions-report-type? ["=" "type" "y"])))))

  (is (= false (t/mentions-report-type? ["null?" "x"])))
  (is (= false (t/mentions-report-type? ["null?" "type"])))

  (is (= false (t/mentions-report-type? ["in" "x" ["array" "y" "z"]])))
  (is (= true (t/mentions-report-type? ["in" "type" ["array" "x" "y"]])))

  
  ;; subqueries - dead end end for this check
  ;; FIXME: Not testing top-level ["from" "nodes" ...] -- add if we keep support
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
                      ["select_reports" expr]]])))

      (is (= false (t/mentions-report-type?
                    ["from" "x"
                     ["extract" "y" expr]])))
      (is (= false (t/mentions-report-type?
                    ["from" ["x"]
                     ["extract" ["y"] expr]])))))

  (doseq [op ["and" "or"]]
    (testing (str "binary operator " op)
      (is (= false (t/mentions-report-type? [op ["=" "x" "y"]])))
      (is (= false (t/mentions-report-type? [op ["=" "w" "x"] ["=" "y" "z"]])))
      (is (= true (t/mentions-report-type? [op ["=" "type" "y"]])))
      (is (= true (t/mentions-report-type? [op ["=" "w" "x"] ["=" "type" "z"]]))))))

(deftest test-random-bits

  (is (= #{:catalog_environment :catalogs :certnames :facts_environment :fs
           :reports :reports_environment :report_statuses}
         (t/qrec-tables nodes-query)))

  (is (= false (t/qrec-involving-reports? inventory-query)))
  (is (= true (t/qrec-involving-reports? nodes-query)))
  (is (= true (t/qrec-involving-reports? report-logs-query)))


  (is (= [inventory-query []]
         (t/maybe-add-agent-report-filter-to-query inventory-query [])))
  
  (is (= [nodes-query ["=" "type" "agent"]]
         (t/maybe-add-agent-report-filter-to-query nodes-query [])))

  (is (= [nodes-query ["=" "type" "report"]]
         (t/maybe-add-agent-report-filter-to-query nodes-query
                                                   ["=" "type" "report"]))))
