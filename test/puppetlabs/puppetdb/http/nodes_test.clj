(ns puppetlabs.puppetdb.http.nodes-test
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.kitchensink.core :refer [keyset]]
            [puppetlabs.puppetdb.testutils :refer [get-request
                                                   paged-results
                                                   deftestseq]]
            [puppetlabs.puppetdb.testutils.nodes :refer [store-example-nodes]]
            [flatland.ordered.map :as omap]
            [puppetlabs.puppetdb.zip :as zip]
            [clojure.core.match :as cm]))

(def endpoints [[:v4 "/v4/nodes"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(defn get-response
  ([endpoint]      (get-response endpoint nil))
  ([endpoint query] (fixt/*app* (get-request endpoint query)))
  ([endpoint query params] (fixt/*app* (get-request endpoint query params))))


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
  [endpoint node-name]
  (-> (get-response endpoint ["=" (certname (get-version endpoint)) node-name])
      :body
      slurp
      (json/parse-string true)
      first))

(defn is-query-result
  [endpoint query expected]
  (let [{:keys [body status]} (get-response endpoint query)
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
      (is (= #{:certname :deactivated :catalog-timestamp :facts-timestamp :report-timestamp
               :catalog-environment :facts-environment :report-environment} (keyset res))
          (str "Query was: " query))
      (is (= (set expected) (set (mapv :certname result)))
          (str "Query was: " query)))

    (is (= status http/status-ok))))

(deftestseq node-queries
  [[version endpoint] endpoints]

  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
    (testing "status objects should reflect fact/catalog activity"
      (testing "when node is active"
        (is (nil? (:deactivated (status-for-node endpoint web1)))))

      (testing "when node has facts, but no catalog"
        (is (:facts-timestamp (status-for-node endpoint web2)))
        (is (nil? (:catalog-timestamp (status-for-node endpoint web2)))))

      (testing "when node has an associated catalog and facts"
        (is (:catalog-timestamp (status-for-node endpoint web1)))
        (is (:facts-timestamp (status-for-node endpoint web1)))))

    (testing "basic equality is supported for name"
      (is-query-result endpoint ["=" (certname version) "web1.example.com"] [web1]))

    (testing "regular expressions are supported for name"
      (is-query-result endpoint ["~" (certname version) "web\\d+.example.com"] [web1 web2])
      (is-query-result endpoint ["~" (certname version) "\\w+.example.com"] [db puppet web1 web2])
      (is-query-result endpoint ["~" (certname version) "example.net"] []))

    (testing "basic equality works for facts, and is based on string equality"
      (is-query-result endpoint ["=" ["fact" "operatingsystem"] "Debian"] [db web1 web2])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] 10000] [web1])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] "10000"] [])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] 10000.0] [web1])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] true] [])
      (is-query-result endpoint ["=" ["fact" "uptime_seconds"] 0] []))

    (testing "missing facts are not equal to anything"
      (is-query-result endpoint ["=" ["fact" "fake_fact"] "something"] [])
      (is-query-result endpoint ["not" ["=" ["fact" "fake_fact"] "something"]] [db puppet web1 web2]))

    (testing "arithmetic works on facts"
      (is-query-result endpoint ["<" ["fact" "uptime_seconds"] 12000] [web1])
      (is-query-result endpoint ["<" ["fact" "uptime_seconds"] 12000.0] [web1])
      (is-query-result endpoint ["and" [">" ["fact" "uptime_seconds"] 10000] ["<" ["fact" "uptime_seconds"] 15000]] [web2])
      (is-query-result endpoint ["<=" ["fact" "uptime_seconds"] 15000] [puppet web1 web2]))

    (testing "regular expressions work on facts"
      (is-query-result endpoint ["~" ["fact" "ipaddress"] "192.168.1.11\\d"] [db puppet])
      (is-query-result endpoint ["~" ["fact" "hostname"] "web\\d"] [web1 web2]))))

(deftestseq test-string-coercion-fail
  [[version endpoint] endpoints]
  (store-example-nodes)
  (let [{:keys [body status]} (get-response endpoint ["<" ["fact" "uptime_seconds"] "12000"])]
    (is (= 400 status))
    (is (re-find #"not allowed on value '12000'" body))))

(defn v4-node-field [version]
  "certname")

(deftestseq basic-node-subqueries
  [[version endpoint] endpoints]
  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
    (doseq [[query expected] {
                              ;; Basic sub-query for fact operatingsystem
                              ["in" (certname version)
                               ["extract" "certname"
                                ["select-facts"
                                 ["and"
                                  ["=" "name" "operatingsystem"]
                                  ["=" "value" "Debian"]]]]]

                              [db web1 web2]

                              ;; Nodes with a class matching their hostname
                              ["in" (certname version)
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
        (is-query-result endpoint query expected)))))

(deftestseq node-subqueries
  [[version endpoint] endpoints]

  (testing "subqueries: valid"
    (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
      (doseq [[query expected] {
                                ;; Nodes with matching select-resources for file/line
                                ["in" (certname version)
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
                         ["in" (certname version)
                          ["extract" "certname"
                           ["select-resources"
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
  [[version endpoint] endpoints]

  (let [expected (store-example-nodes)]

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str endpoint " should support paging through nodes " label " counts")
        (let [results (paged-results
                       {:app-fn  fixt/*app*
                        :path    endpoint
                        :limit   1
                        :total   (count expected)
                        :include-total  count?
                        :params {:order-by (json/generate-string [{:field (certname version) :order "asc"}])}})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                 (set (map :certname results)))))))))

(deftestseq node-timestamp-queries
  [[version endpoint] endpoints]

  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)
        web1-catalog-ts (:catalog-timestamp (status-for-node endpoint web1))
        web1-facts-ts (:facts-timestamp (status-for-node endpoint web1))
        web1-report-ts (:report-timestamp (status-for-node endpoint web1))]

    (testing "basic query for timestamps"

      (is-query-result endpoint ["=" "facts-timestamp" web1-facts-ts] [web1])
      (is-query-result endpoint [">" "facts-timestamp" web1-facts-ts] [web2 db puppet])
      (is-query-result endpoint [">=" "facts-timestamp" web1-facts-ts] [web1 web2 db puppet])

      (is-query-result endpoint ["=" "catalog-timestamp" web1-catalog-ts] [web1])
      (is-query-result endpoint [">" "catalog-timestamp" web1-catalog-ts] [db puppet])
      (is-query-result endpoint [">=" "catalog-timestamp" web1-catalog-ts] [web1 db puppet])

      (is-query-result endpoint ["=" "report-timestamp" web1-report-ts] [web1])
      (is-query-result endpoint [">" "report-timestamp" web1-report-ts] [db puppet])
      (is-query-result endpoint [">=" "report-timestamp" web1-report-ts] [web1 db puppet]))))

(def invalid-projection-queries
  (omap/ordered-map
     ;; Top level extract using invalid fields should throw an error
     ["extract" "nothing" ["~" "certname" ".*"]]
     #"Can't extract unknown 'nodes' field 'nothing'.*Acceptable fields are.*"

     ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
     #"Can't extract unknown 'nodes' fields: 'nothing', 'nothing2'.*Acceptable fields are.*"))

(deftestseq invalid-projections
  [[version endpoint] endpoints]
  (doseq [[query msg] invalid-projection-queries]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (get-response endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))
