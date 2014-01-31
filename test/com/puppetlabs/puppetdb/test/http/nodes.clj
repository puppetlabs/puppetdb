(ns com.puppetlabs.puppetdb.test.http.v3.nodes
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.utils :only [keyset]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [get-request paged-results]]
        [com.puppetlabs.puppetdb.testutils.nodes :only [store-example-nodes]]))

(def endpoints ["/v2/nodes" "/v3/nodes"])

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([endpoint]      (get-response endpoint nil))
  ([endpoint query] (*app* (get-request endpoint query)))
  ([endpoint query params] (*app* (get-request endpoint query params))))

(defn is-query-result
  [endpoint query expected]
  (let [{:keys [body status]} (get-response endpoint query)
        result (try
                 (json/parse-string body true)
                 (catch com.fasterxml.jackson.core.JsonParseException e
                   body))]
    (doseq [res result]
      (is (= #{:name :deactivated :catalog_timestamp :facts_timestamp :report_timestamp} (keyset res))))
    (is (= status pl-http/status-ok))
    (is (= expected (mapv :name result)))))

(deftest node-endpoint-tests
  (doseq [endpoint endpoints]
    (deftest node-queries
      (testing (str "node queries for " endpoint ":")
        (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
          (testing "status objects should reflect fact/catalog activity"
            (let [status-for-node #(first (json/parse-string (:body (get-response endpoint ["=" "name" %])) true))]
              (testing "when node is active"
                (is (nil? (:deactivated (status-for-node web1)))))

              (testing "when node has facts, but no catalog"
                (is (:facts_timestamp (status-for-node web2)))
                (is (nil? (:catalog_timestamp (status-for-node web2)))))

              (testing "when node has an associated catalog and facts"
                (is (:catalog_timestamp (status-for-node web1)))
                (is (:facts_timestamp (status-for-node web1))))))

          (testing "basic equality is supported for name"
            (is-query-result ["=" "name" "web1.example.com"] [web1]))

          (testing "regular expressions are supported for name"
            (is-query-result ["~" "name" "web\\d+.example.com"] [web1 web2])
            (is-query-result ["~" "name" "\\w+.example.com"] [db puppet web1 web2])
            (is-query-result ["~" "name" "example.net"] []))

          (testing "basic equality works for facts, and is based on string equality"
            (is-query-result ["=" ["fact" "operatingsystem"] "Debian"] [db web1 web2])
            (is-query-result ["=" ["fact" "uptime_seconds"] 10000] [web1])
            (is-query-result ["=" ["fact" "uptime_seconds"] "10000"] [web1])
            (is-query-result ["=" ["fact" "uptime_seconds"] 10000.0] [])
            (is-query-result ["=" ["fact" "uptime_seconds"] true] [])
            (is-query-result ["=" ["fact" "uptime_seconds"] 0] []))

          (testing "missing facts are not equal to anything"
            (is-query-result ["=" ["fact" "fake_fact"] "something"] [])
            (is-query-result ["not" ["=" ["fact" "fake_fact"] "something"]] [db puppet web1 web2]))

          (testing "arithmetic works on facts"
            (is-query-result ["<" ["fact" "uptime_seconds"] 12000] [web1])
            (is-query-result ["<" ["fact" "uptime_seconds"] 12000.0] [web1])
            (is-query-result ["<" ["fact" "uptime_seconds"] "12000"] [web1])
            (is-query-result ["and" [">" ["fact" "uptime_seconds"] 10000] ["<" ["fact" "uptime_seconds"] 15000]] [web2])
            (is-query-result ["<=" ["fact" "uptime_seconds"] 15000] [puppet web1 web2]))

          (testing "regular expressions work on facts"
            (is-query-result ["~" ["fact" "ipaddress"] "192.168.1.11\\d"] [db puppet])
            (is-query-result ["~" ["fact" "hostname"] "web\\d"] [web1 web2]))))

    (deftest node-subqueries
      (testing (str "valid node subqueries for " endpoint ":")
        (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
            (doseq [[query expected] {
                      ;; Basic sub-query for fact operatingsystem
                      ["in" "name"
                       ["extract" "certname"
                        ["select-facts"
                         ["and"
                          ["=" "name" "operatingsystem"]
                          ["=" "value" "Debian"]]]]]

                      [db web1 web2]

                      ;; Nodes with a class matching their hostname
                      ["in" "name"
                       ["extract" "certname"
                        ["select-facts"
                         ["and"
                          ["=" "name" "hostname"]
                          ["in" "value"
                           ["extract" "title"
                            ["select-resources"
                             ["and"
                              ["=" "type" "Class"]]]]]]]]]

                      [web1]}]
              (testing (str "query: " query " is supported")
                (is-query-result query expected)))))))))
