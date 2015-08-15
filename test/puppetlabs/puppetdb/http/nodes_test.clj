(ns puppetlabs.puppetdb.http.nodes-test
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :refer [keyset]]
            [puppetlabs.puppetdb.testutils :refer [get-request
                                                   paged-results
                                                   deftestseq
                                                   query-request]]
            [puppetlabs.puppetdb.testutils.nodes :refer [store-example-nodes]]
            [flatland.ordered.map :as omap]))

(def endpoints [[:v4 "/v4/nodes"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

;; RETRIEVAL
(defn query-response
  ([method endpoint]      (query-response method endpoint nil))
  ([method endpoint query] (query-response method endpoint query {}))
  ([method endpoint query params] (fixt/*app* (query-request method endpoint query {:params params}))))

(defn query-result
  ([method endpoint]
   (query-result method endpoint nil))
  ([method endpoint query]
   (query-result method endpoint query {}))
  ([method endpoint query params]
   (-> (query-response method endpoint query params)
       :body
       slurp
       (json/parse-string true))))

;; HELPERS
(defn get-version
  "Lookup version from endpoint uri"
  [endpoint]
  (ffirst (filter (fn [[version version-endpoint]]
                    (= version-endpoint endpoint))
                  endpoints)))

(defn certname [version]
  "certname")

(defn status-for-node
  "Returns status information for the given `node-name`"
  [method endpoint node-name]
  (-> (query-response method endpoint ["=" (certname (get-version endpoint)) node-name])
      :body
      slurp
      (json/parse-string true)
      first))

(defn is-query-result
  [method endpoint query expected]
  (let [{:keys [body status]} (query-response method endpoint query)
        body   (if (string? body)
                 body
                 (slurp body))
        result (try
                 (json/parse-string body true)
                 (catch com.fasterxml.jackson.core.JsonParseException e
                   body))]

    (is (= (count result)
           (count expected))
        (str "Query was: " query))

    (doseq [res result]
      (is (= #{:certname :deactivated :expired :catalog_timestamp :facts_timestamp :report_timestamp
               :catalog_environment :facts_environment :report_environment
               :latest_report_status :latest_report_hash} (keyset res))
          (str "Query was: " query))
      (is (= (set expected) (set (mapv :certname result)))
          (str "Query was: " query)))

    (is (= status http/status-ok))))

;; TESTS

(deftestseq node-queries
  [[version endpoint] endpoints
   method [:get :post]]

  (let [status-for-node' #(status-for-node method endpoint %)
        {:keys [web1 web2 db puppet]} (store-example-nodes)
        is-query-result' (fn [query expected] (is-query-result method endpoint query expected))]
    (testing "status objects should reflect fact/catalog activity"
      (testing "when node is active"
        (let [status (status-for-node' web1)]
          (is (nil? (:deactivated status)))
          (is (nil? (:expired status)))))

      (testing "when node has facts, but no catalog"
        (is (:facts_timestamp (status-for-node' web2)))
        (is (nil? (:catalog_timestamp (status-for-node' web2)))))

      (testing "when node has an associated catalog and facts"
        (is (:catalog_timestamp (status-for-node' web1)))
        (is (:facts_timestamp (status-for-node' web1)))))

    (testing "basic equality is supported for name"
      (is-query-result' ["=" (certname version) "web1.example.com"] [web1]))

    (testing "regular expressions are supported for name"
      (is-query-result' ["~" (certname version) "web\\d+.example.com"] [web1 web2])
      (is-query-result' ["~" (certname version) "\\w+.example.com"] [db puppet web1 web2])
      (is-query-result' ["~" (certname version) "example.net"] []))

    (testing "querying on latest report hash works"
      (let [cert-hashes (query-result method endpoint ["extract"
                                                       ["certname" "latest_report_hash"]
                                                       ["~" "certname" ".*"]])]
        (doseq [{:keys [certname latest_report_hash]} cert-hashes]
          (let [body (query-result method endpoint ["=" "latest_report_hash" latest_report_hash])]
            (is (= certname (:certname (first body))))))))

    (testing "querying on latest report status works"
      (is-query-result' ["=" "latest_report_status" "success"] [])
      (is-query-result' ["=" "latest_report_status" "failure"] [])
      (is-query-result' ["=" "latest_report_status" "unchanged"] [web1 db puppet]))

    (testing "basic equality works for facts, and is based on string equality"
      (is-query-result' ["=" ["fact" "operatingsystem"] "Debian"] [db web1 web2])
      (is-query-result' ["=" ["fact" "uptime_seconds"] 10000] [web1])
      (is-query-result' ["=" ["fact" "uptime_seconds"] "10000"] [])
      (is-query-result' ["=" ["fact" "uptime_seconds"] 10000.0] [web1])
      (is-query-result' ["=" ["fact" "uptime_seconds"] true] [])
      (is-query-result' ["=" ["fact" "uptime_seconds"] 0] []))

    (testing "missing facts are not equal to anything"
      (is-query-result' ["=" ["fact" "fake_fact"] "something"] [])
      (is-query-result' ["not" ["=" ["fact" "fake_fact"] "something"]] [db puppet web1 web2]))

    (testing "arithmetic works on facts"
      (is-query-result' ["<" ["fact" "uptime_seconds"] 12000] [web1])
      (is-query-result' ["<" ["fact" "uptime_seconds"] 12000.0] [web1])
      (is-query-result' ["and" [">" ["fact" "uptime_seconds"] 10000] ["<" ["fact" "uptime_seconds"] 15000]] [web2])
      (is-query-result' ["<=" ["fact" "uptime_seconds"] 15000] [puppet web1 web2]))

    (testing "regular expressions work on facts"
      (is-query-result' ["~" ["fact" "ipaddress"] "192.168.1.11\\d"] [db puppet])
      (is-query-result' ["~" ["fact" "hostname"] "web\\d"] [web1 web2]))))

(deftestseq test-string-coercion-fail
  [[version endpoint] endpoints
   method [:get :post]]
  (store-example-nodes)
  (let [{:keys [body status]} (query-response method endpoint ["<" ["fact" "uptime_seconds"] "12000"])]
    (is (= 400 status))
    (is (re-find #"not allowed on value '12000'" body))))

(defn v4-node-field [version]
  "certname")

(deftestseq basic-node-subqueries
  [[version endpoint] endpoints
   method [:get :post]]
  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
    (doseq [[query expected] {
                              ;; Basic sub-query for fact operatingsystem
                              ["in" (certname version)
                               ["extract" "certname"
                                ["select_facts"
                                 ["and"
                                  ["=" "name" "operatingsystem"]
                                  ["=" "value" "Debian"]]]]]

                              [db web1 web2]

                              ;; Nodes with a class matching their hostname
                              ["in" (certname version)
                               ["extract" "certname"
                                ["select_facts"
                                 ["and"
                                  ["=" "name" "hostname"]
                                  ["in" "value"
                                   ["extract" "title"
                                    ["select_resources"
                                     ["and"
                                      ["=" "type" "Class"]]]]]]]]]

                              [web1]}]
      (testing (str "query: " query " is supported")
        (is-query-result method endpoint query expected)))))

(deftestseq node-subqueries
  [[version endpoint] endpoints
   method [:get :post]]

  (testing "subqueries: valid"
    (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
      (doseq [[query expected] {
                                ;; Nodes with matching select-resources for file/line
                                ["in" (certname version)
                                 ["extract" "certname"
                                  ["select_resources"
                                   ["and"
                                    ["=" "file" "/etc/puppet/modules/settings/manifests/init.pp"]
                                    ["=" "line" 1]]]]]

                                ["db.example.com" "puppet.example.com" "web1.example.com"]}]
        (testing (str "query: " query " is supported")
          (is-query-result method endpoint query expected)))))

  (testing "subqueries: invalid"
    (doseq [[query msg] {
                         ;; Ensure the v2 version of sourcefile/sourceline returns
                         ;; a proper error.
                         ["in" (certname version)
                          ["extract" "certname"
                           ["select_resources"
                            ["and"
                             ["=" "sourcefile" "/etc/puppet/modules/settings/manifests/init.pp"]
                             ["=" "sourceline" 1]]]]]

                         (re-pattern (format "'sourcefile' is not a queryable object.*" (last (name version))))}]
      (testing (str endpoint " query: " query " should fail with msg: " msg)
        (let [request (get-request endpoint (json/generate-string query))
              {:keys [status body] :as result} (fixt/*app* request)]
          (is (= status http/status-bad-request))
          (is (re-find msg body)))))))

(deftestseq node-query-paging
  [[version endpoint] endpoints
   method [:get :post]]

  (let [expected (store-example-nodes)]

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str endpoint " should support paging through nodes " label " counts")
        (let [results (paged-results method
                       {:app-fn  fixt/*app*
                        :path    endpoint
                        :limit   1
                        :total   (count expected)
                        :include_total  count?
                        :params {:order_by (if (= :get method)
                                             (json/generate-string [{:field (certname version) :order "asc"}])
                                             [{:field (certname version) :order "asc"}])}})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                 (set (map :certname results)))))))))

(deftestseq node-timestamp-queries
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)
        web1-catalog-ts (:catalog_timestamp (status-for-node method endpoint web1))
        web1-facts-ts (:facts_timestamp (status-for-node method endpoint web1))
        web1-report-ts (:report_timestamp (status-for-node method endpoint web1))
        is-query-result' (fn [query expected] (is-query-result method endpoint query expected))]

    (testing "basic query for timestamps"

      (is-query-result' ["=" "facts_timestamp" web1-facts-ts] [web1])
      (is-query-result' [">" "facts_timestamp" web1-facts-ts] [web2 db puppet])
      (is-query-result' [">=" "facts_timestamp" web1-facts-ts] [web1 web2 db puppet])

      (is-query-result' ["=" "catalog_timestamp" web1-catalog-ts] [web1])
      (is-query-result' [">" "catalog_timestamp" web1-catalog-ts] [db puppet])
      (is-query-result' [">=" "catalog_timestamp" web1-catalog-ts] [web1 db puppet])

      (is-query-result' ["=" "report_timestamp" web1-report-ts] [web1])
      (is-query-result' [">" "report_timestamp" web1-report-ts] [db puppet])
      (is-query-result' [">=" "report_timestamp" web1-report-ts] [web1 db puppet]))))

(def invalid-projection-queries
  (omap/ordered-map
     ;; Top level extract using invalid fields should throw an error
     ["extract" "nothing" ["~" "certname" ".*"]]
     #"Can't extract unknown 'nodes' field 'nothing'.*Acceptable fields are.*"

     ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
     #"Can't extract unknown 'nodes' fields: 'nothing', 'nothing2'.*Acceptable fields are.*"))

(deftestseq invalid-projections
  [[version endpoint] endpoints
   method [:get :post]
   [query msg] invalid-projection-queries]
  (testing (str "query: " query " should fail with msg: " msg)
    (let [{:keys [status body] :as result} (query-response method endpoint query)]
      (is (re-find msg body))
      (is (= status http/status-bad-request)))))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
    "/v4/nodes" (omap/ordered-map
                  ["~" "certname" "*abc"]
                  #".*invalid regular expression: quantifier operand invalid"

                  ["~" "certname" "[]"]
                  #".*invalid regular expression: brackets.*not balanced")))

(deftestseq ^{:hsqldb false} pg-invalid-regexps
  [[version endpoint] endpoints
   method [:get :post]
   [query msg] (get pg-versioned-invalid-regexps endpoint)]
  (testing (str "query: " query " should fail with msg: " msg)
    (let [{:keys [status body] :as result} (query-response method endpoint query)]
      (is (re-find msg body))
      (is (= status http/status-bad-request)))))

(def no-parent-endpoints [[:v4 "/v4/nodes/foo/facts"]
                          [:v4 "/v4/nodes/foo/resources"]])

(deftestseq unknown-parent-handling
  [[version endpoint] no-parent-endpoints
   method [:get :post]]
  (let [{:keys [status body] :as result} (query-response method endpoint)]
    (is (= status http/status-not-found))
    (is (= {:error "No information is known about node foo"} (json/parse-string body true)))))
