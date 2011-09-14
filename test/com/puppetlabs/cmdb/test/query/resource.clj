(ns com.puppetlabs.cmdb.test.query.resource
  (:require [com.puppetlabs.cmdb.query.resource :as s]
            [clj-json.core :as json]
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


(deftest query->sql-where
  (testing "comparisons"
    (doseq [[input expect] {;; without a path
                            ["=" "title" "whatever"]
                            ["(title = ?)" "whatever"]
                            ;; with a path to the field
                            ["=" ["node" "certname"] "example"]
                            ["(node.certname = ?)" "example"]
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
           ["NOT (node.certname = ?) AND (exported = ?) AND (tag = ?)"
            "example.local" true "yellow"]))))
