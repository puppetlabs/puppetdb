(ns com.puppetlabs.cmdb.test.query.resource
  (:require [com.puppetlabs.cmdb.query.resource :as s]
            [clojure.data.json :as json]
            [clojure.string :as string]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request))

;;;; Test the resource listing handlers.
(def *handler* s/resource-list-handler)
(def *c-t*     s/resource-list-c-t)

(defn get-request
  ([path] (get-request path nil))
  ([path query]
     (let [request (request :get path query)
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" *c-t*)))))

(deftest valid-query?
  (testing "invalid input"
    (doseq [input [nil "" 12 #{1 2} [nil]]]
      (is (not (s/valid-query? input)) (str input))))
  (testing "almost valid input"
    (doseq [input [["="
                    ["=" "whatever"]
                    ["=" "a" "b" "c"]
                    ["=" "certname" ["a" "b"]]]]]
      (is (not (s/valid-query? input)) (str input))))
  (testing "simple comparison"
    (doseq [input [ ["=" "certname" "foo"]
                    ["=" "title" "foo"]
                    ["=" ["parameter" "ensure"] "foo"] ]]
      (is (s/valid-query? input) (str input))))
  (testing "combining terms"
    (doseq [op ["and" "or" "AND" "OR"]]
      (let [pattern [op ["=" "certname" "foo"] ["=" "certname" "bar"]]]
        (is (s/valid-query? pattern) (str pattern)))))
  (testing "negating terms"
    (doseq [input [ ["=" "certname" "foo"]
                    ["=" "title" "foo"]
                    ["=" ["parameter" "ensure"] "foo"] ]]
      (is (s/valid-query? ["not" input]) (str ["not" input]))))
  (testing "real world examples"
    (is (s/valid-query? ["and" ["not" ["=" "certname" "example.local"]]
                         ["=" "type" "File"]
                         ["=" "title" "/etc/passwd"]]))
    (is (s/valid-query? ["and" ["not" ["=" "certname" "example.local"]]
                         ["=" "type" "File"]
                         ["not" ["=" "tag" "fitzroy"]]]))))


(comment
(deftest query->sql-where
  (testing "comparisons"
    (doseq [[input expect] {;; without a path
                            ["=" "title" "whatever"]
                            ["(title = ?)" "whatever"]
                            ;; with a path to the field
                            ["=" ["node" "certname"] "example"]
                            ["(node_certname = ?)" "example"]
                            }]
      (is (= (s/query->sql-where input) expect))))
  (testing "degenerate grouping"
    (is (= (s/query->sql-where ["and" ["=" "tag" "one"]]) ["(tag = ?)" "one"]))
    (is (= (s/query->sql-where ["or"  ["=" "tag" "one"]]) ["(tag = ?)" "one"])))
  (testing "grouping"
    (doseq [[input expect] {;; simple and, or
                            ["and" ["=" "title" "one"] ["=" "title" "two"]]
                            ["(title = ?) AND (title = ?)" "one" "two"]
                            ["or" ["=" "title" "one"] ["=" "title" "two"]]
                            ["(title = ?) OR (title = ?)" "one" "two"]
                            ;; more terms...
                            ["and" ["=" "title" "one"]
                                   ["=" "title" "two"]
                                   ["=" "title" "three"]
                                   ["=" "title" "two"]
                                   ["=" "title" "one"]]
                            [(str "(title = ?) AND (title = ?) AND (title = ?)"
                                  " AND (title = ?) AND (title = ?)")
                             "one" "two" "three" "two" "one"]
                            }]
      (is (= (s/query->sql-where input) expect) (str input))))
  (testing "negation"
    (doseq [[input expect] {;; negate one item
                            ["not" ["=" "title" "whatever"]]
                            ["NOT (title = ?)" "whatever"]
                            ;; multiple items
                            ["not" ["=" "title" "whatever"] ["=" "title" "banana"]]
                            ["NOT ((title = ?) OR (title = ?))" "whatever" "banana"]
                            }]
      (is (= (s/query->sql-where input) expect) (str input))))
  (testing "real world query"
    (is (= (s/query->sql-where ["and"
                                ["not" ["=" ["node" "certname"] "example.local"]]
                                ["=" "exported" true]
                                ["=" "tag" "yellow"]])
           ["NOT (node_certname = ?) AND (exported = ?) AND (tag = ?)"
            "example.local" true "yellow"])))
  (testing "composite field formatting"
    (is (= (s/query->sql-where ["=" ["node" "certname"] "example.local"])
           ["(node_certname = ?)" "example.local"]))
    (is (= (s/query->sql-where ["=" ["parameter" "ensure"] "running"])
           ["(parameter_name = ? AND parameter_value = ?)" "ensure" "running"]))))
)

(deftest query->sql
  (testing "comparisons"
    ;; simple, local attributes
    (is (= (s/query->sql ["=" "title" "whatever"])
           ["(SELECT DISTINCT hash FROM resources WHERE title = ?)" "whatever"]))
    ;; with a path to the field
    (let [[sql & params] (s/query->sql ["=" ["node" "certname"] "example"])]
      (is (= params ["example"]))
      (is (re-find #"JOIN certname_resources" sql))
      (is (re-find #"WHERE certname_resources.certname = \?" sql)))
    (let [[sql & params] (s/query->sql ["=" "tag" "foo"])]
      (is (re-find #"SELECT DISTINCT hash FROM resources" sql))
      (is (re-find #"JOIN resource_tags" sql))
      (is (= params ["foo"]))))
  (testing "order of params in grouping"
    (let [[sql & params] (s/query->sql ["and"
                                        ["=" "type" "foo"]
                                        ["=" "type" "bar"]
                                        ["=" "type" "baz"]])]
      (is (= params ["foo" "bar" "baz"]))))
  (let [terms [["=" "title" "one"]
               ["=" "type" "two"]
               ["=" "tag" "three"]
               ["=" ["node" "certname"] "four"]
               ["=" ["parameter" "ensure"] "five"]
               ["=" ["parameter" "banana"] "yumm"]]]
    (testing "simple {and, or} grouping"
      (doall
       (for [[op join] {"and" "INTERSECT" "or" "UNION"}
             one terms two terms]
         (let [[sql1 & param1] (s/query->sql one)
               [sql2 & param2] (s/query->sql two)
               [sql & params] (s/query->sql [op one two])]
           (is (= sql (str "(" sql1 " " join " " sql2 ")")))
           (is (= params (concat param1 param2)))))))
    (testing "simple {and, or} grouping with many terms"
      (doall
       (for [[op join] {"and" " INTERSECT " "or" " UNION "}]
         (let [terms-  (map s/query->sql terms)
               sql-    (str "(" (string/join join (map first terms-)) ")")
               params- (reduce concat (map rest terms-))
               [sql & params] (s/query->sql (apply (partial vector op) terms))]
           (is (= sql sql-))
           (is (= params params-)))))))
  (testing "negation"
    (let [[sql & params] (s/query->sql ["not" ["=" "type" "foo"]])]
      (is (= sql (str "(SELECT DISTINCT hash FROM resources EXCEPT "
                      "((SELECT DISTINCT hash FROM resources WHERE type = ?)))")))
      (is (= params ["foo"])))
    (let [[sql & params] (s/query->sql ["not" ["=" "type" "foo"]
                                              ["=" "title" "bar"]])]
      (is (= sql (str "(SELECT DISTINCT hash FROM resources EXCEPT "
                      "("
                      "(SELECT DISTINCT hash FROM resources WHERE type = ?)"
                      " UNION "
                      "(SELECT DISTINCT hash FROM resources WHERE title = ?)"
                      ")"
                      ")")))
      (is (= params ["foo" "bar"]))))
  (testing "real world query"
    (let [[sql & params]
          (s/query->sql ["and"
                         ["not" ["=" ["node" "certname"] "example.local"]]
                         ["=" "exported" true]
                         ["=" ["parameter" "ensure"] "yellow"]])]
      (is (= sql (str "("
                      ;; top level and not certname
                      "(SELECT DISTINCT hash FROM resources EXCEPT ("
                      "(SELECT DISTINCT hash FROM resources JOIN certname_resources "
                      "ON certname_resources.resource = resources.hash "
                      "WHERE certname_resources.certname = ?)"
                      "))"
                      ;; exported
                      " INTERSECT "
                      "(SELECT DISTINCT hash FROM resources "
                      "WHERE exported = ?)"
                      ;; parameter match
                      " INTERSECT "
                      "(SELECT DISTINCT hash FROM resources JOIN resource_params "
                      "ON resource_params.resource = resources.hash "
                      "WHERE resource_params.name = ? AND "
                      "resource_params.value = ?"
                      ")"
                      ")")))
      (is (= params ["example.local" true "ensure" "yellow"])))))
