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


