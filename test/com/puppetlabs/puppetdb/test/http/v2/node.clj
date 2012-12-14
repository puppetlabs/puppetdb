(ns com.puppetlabs.puppetdb.test.http.v2.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.utils :only (keyset)]
        [clj-time.core :only [now]]
        com.puppetlabs.puppetdb.examples
        com.puppetlabs.puppetdb.fixtures))

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-request
  ([path] (get-request path nil))
  ([path query]
     (let [request (if query
                     (request :get path
                              {"query" (json/generate-string query)})
                     (request :get path))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" c-t)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v2/nodes" query))))

(defn is-query-result
  [query expected]
  (let [{:keys [body status]} (get-response query)
        result (try
                 (json/parse-string body true)
                 (catch com.fasterxml.jackson.core.JsonParseException e
                   body))]
    (doseq [res result]
      (is (= #{:name :deactivated :catalog_timestamp :facts_timestamp :report_timestamp} (keyset res))))
    (is (= status pl-http/status-ok))
    (is (= expected (mapv :name result))
        (str query))))

(deftest node-queries
  (let [web1 "web1.example.com"
        web2 "web2.example.com"
        puppet "puppet.example.com"
        db "db.example.com"
        catalog (:empty catalogs)
        web1-catalog (update-in catalog [:resources] conj {{:type "Class" :title "web"} {:type "Class" :title "web1" :exported false}})
        puppet-catalog  (update-in catalog [:resources] conj {{:type "Class" :title "puppet"} {:type "Class" :title "puppetmaster" :exported false}})
        db-catalog  (update-in catalog [:resources] conj {{:type "Class" :title "db"} {:type "Class" :title "mysql" :exported false}})]
    (scf-store/add-certname! web1)
    (scf-store/add-certname! web2)
    (scf-store/add-certname! puppet)
    (scf-store/add-certname! db)
    (scf-store/add-facts! web1 {"ipaddress" "192.168.1.100" "hostname" "web1" "operatingsystem" "Debian" "uptime_seconds" 10000} (now))
    (scf-store/add-facts! web2 {"ipaddress" "192.168.1.101" "hostname" "web2" "operatingsystem" "Debian" "uptime_seconds" 13000} (now))
    (scf-store/add-facts! puppet {"ipaddress" "192.168.1.110" "hostname" "puppet" "operatingsystem" "RedHat" "uptime_seconds" 15000} (now))
    (scf-store/add-facts! db {"ipaddress" "192.168.1.111" "hostname" "db" "operatingsystem" "Debian"} (now))
    (scf-store/replace-catalog! (assoc web1-catalog :certname web1) (now))
    (scf-store/replace-catalog! (assoc puppet-catalog :certname puppet) (now))
    (scf-store/replace-catalog! (assoc db-catalog :certname db) (now))

    (testing "status objects should reflect fact/catalog activity"
      (let [status-for-node #(first (json/parse-string (:body (get-response ["=" "name" %])) true))]
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
      (is-query-result ["~" ["fact" "hostname"] "web\\d"] [web1 web2]))

    (testing "subqueries are supported"
      (doseq [[query expected] {["in" "name"
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
        (is-query-result query expected)))))
