(ns com.puppetlabs.puppetdb.test.http.nodes
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.kitchensink.core :refer [keyset]]
            [com.puppetlabs.puppetdb.testutils :refer [get-request paged-results
                                                       deftestseq]]
            [com.puppetlabs.puppetdb.testutils.nodes :refer [store-example-nodes]]))

(def endpoints [[:v2 "/v2/nodes"]
                [:v3 "/v3/nodes"]
                [:v4 "/v4/nodes"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(defn get-response
  ([endpoint]      (get-response endpoint nil))
  ([endpoint query] (fixt/*app* (get-request endpoint query)))
  ([endpoint query params] (fixt/*app* (get-request endpoint query params))))

(defn is-query-result
  [endpoint query expected]
  (let [{:keys [body status]} (get-response endpoint query)
        result (try
                 (json/parse-string body true)
                 (catch com.fasterxml.jackson.core.JsonParseException e
                   body))]
    (doseq [res result]
      (case endpoint
        ("/v2/nodes" "/v3/nodes")
        (is (= #{:name :deactivated :catalog_timestamp :facts_timestamp :report_timestamp} (keyset res)))

        (is (= #{:name :deactivated :catalog-timestamp :facts-timestamp :report-timestamp
                 :catalog-environment :facts-environment :report-environment} (keyset res)))))
    (is (= status pl-http/status-ok))
    (is (= (set expected) (set (mapv :name result))))))

(deftestseq node-queries
  [[version endpoint] endpoints]

  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
    (testing "status objects should reflect fact/catalog activity"
      (let [status-for-node #(first (json/parse-string (:body (get-response endpoint ["=" "name" %])) true))]
        (testing "when node is active"
          (is (nil? (:deactivated (status-for-node web1)))))

        (testing "when node has facts, but no catalog"
          (case version
            (:v2 :v3)
            (do
              (is (:facts_timestamp (status-for-node web2)))
              (is (nil? (:catalog_timestamp (status-for-node web2)))))

            (do
              (is (:facts-timestamp (status-for-node web2)))
              (is (nil? (:catalog-timestamp (status-for-node web2)))))))

        (testing "when node has an associated catalog and facts"
          (case version
            (:v2 :v3)
            (do
              (is (:catalog_timestamp (status-for-node web1)))
              (is (:facts_timestamp (status-for-node web1))))

            (do
              (is (:catalog-timestamp (status-for-node web1)))
              (is (:facts-timestamp (status-for-node web1))))))))

    (testing "basic equality is supported for name"
      (is-query-result endpoint ["=" "name" "web1.example.com"] [web1]))

    (testing "regular expressions are supported for name"
      (is-query-result endpoint ["~" "name" "web\\d+.example.com"] [web1 web2])
      (is-query-result endpoint ["~" "name" "\\w+.example.com"] [db puppet web1 web2])
      (is-query-result endpoint ["~" "name" "example.net"] []))

    (testing "basic equality works for facts, and is based on string equality"
      (is-query-result endpoint ["=" ["fact" "operatingsystem"] "Debian"] [db web1 web2])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] 10000] [web1])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] "10000"] [web1])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] 10000.0] [])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] true] [])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] 0] []))

    (testing "missing facts are not equal to anything"
      (is-query-result endpoint ["=" ["fact" "fake_fact"] "something"] [])
      (is-query-result endpoint ["not" ["=" ["fact" "fake_fact"] "something"]] [db puppet web1 web2]))

    (testing "arithmetic works on facts"
      (is-query-result endpoint ["<" ["fact" "uptime_seconds"] 12000] [web1])
      (is-query-result endpoint ["<" ["fact" "uptime_seconds"] 12000.0] [web1])
      (is-query-result endpoint ["<" ["fact" "uptime_seconds"] "12000"] [web1])
      (is-query-result endpoint ["and" [">" ["fact" "uptime_seconds"] 10000] ["<" ["fact" "uptime_seconds"] 15000]] [web2])
      (is-query-result endpoint ["<=" ["fact" "uptime_seconds"] 15000] [puppet web1 web2]))

    (testing "regular expressions work on facts"
      (is-query-result endpoint ["~" ["fact" "ipaddress"] "192.168.1.11\\d"] [db puppet])
      (is-query-result endpoint ["~" ["fact" "hostname"] "web\\d"] [web1 web2]))))

(deftestseq node-subqueries
  [[version endpoint] endpoints]

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
        (is-query-result version query expected)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; :v3 & v4 tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftestseq node-subqueries
  [[version endpoint] endpoints
   :when (not= version :v2)]

  (testing "subqueries: valid"
    (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
        (doseq [[query expected] {
                  ;; Nodes with matching select-resources for file/line
                  ["in" "name"
                   ["extract" "certname"
                    ["select-resources"
                     ["and"
                      ["=" "file" "/etc/puppet/modules/settings/manifests/init.pp"]
                      ["=" "line" 1]]]]]

                  ["db.example.com" "puppet.example.com" "web1.example.com"]}]
          (testing (str "query: " query " is supported")
            (is-query-result endpoint query expected)))))

  (testing "subqueries: invalid"
    (doseq [[query msg] {
              ;; Ensure the v2 version of sourcefile/sourceline returns
              ;; a proper error.
              ["in" "name"
               ["extract" "certname"
                ["select-resources"
                 ["and"
                  ["=" "sourcefile" "/etc/puppet/modules/settings/manifests/init.pp"]
                  ["=" "sourceline" 1]]]]]

              (format "'sourcefile' is not a queryable object for resources in the version %s API" (last (name version)))}]
      (testing (str endpoint " query: " query " should fail with msg: " msg)
        (let [request (get-request endpoint (json/generate-string query))
              {:keys [status body] :as result} (fixt/*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body msg)))))))

(deftestseq node-query-paging
  [[version endpoint] endpoints
   :when (not= version :v2)]

  (let [expected (store-example-nodes)]

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str endpoint " should support paging through nodes " label " counts")
        (let [results (paged-results
                        {:app-fn  fixt/*app*
                         :path    endpoint
                         :limit   1
                         :total   (count expected)
                         :include-total  count?})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                (set (map :name results)))))))))
