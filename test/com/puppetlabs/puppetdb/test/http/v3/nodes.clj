(ns com.puppetlabs.puppetdb.test.http.v3.nodes
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.utils :only [keyset]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [get-request paged-results]]
        [com.puppetlabs.puppetdb.testutils.nodes :only [store-example-nodes]]))

(def endpoint "/v3/nodes")

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request endpoint query))))

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
    (is (= expected (mapv :name result)))))

(deftest node-subqueries
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
            (is-query-result query expected)))))

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

              "sourcefile is not a queryable object for resources"}]
      (testing (str "query: " query " should fail with msg: " msg)
        (let [request (get-request endpoint (json/generate-string query))
              {:keys [status body] :as result} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body msg)))))))

(deftest node-query-paging
  (let [expected (store-example-nodes)]

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through nodes " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    endpoint
                         :limit   1
                         :total   (count expected)
                         :include-total  count?})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                (set (map :name results)))))))))
