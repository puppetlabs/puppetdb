(ns puppetlabs.puppetdb.http.fact-names-test
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.test :refer :all]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.testutils :refer [paged-results deftestseq
                                                   parse-result]]
            [puppetlabs.puppetdb.testutils.http :refer [query-response order-param]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]))

(def fact-name-endpoints [[:v4 "/v4/fact-names"]])

(def fact-path-endpoints [[:v4 "/v4/fact-paths"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(deftestseq fact-names-endpoint-tests
  [[version endpoint] fact-name-endpoints
   method [:get :post]]

  (let [facts1 {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "operatingsystem" "Debian"
                "uptime_seconds" "4000"}
        facts2 {"domain" "testing.com"
                "hostname" "foo2"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" "6000"}
        facts3 {"domain" "testing.com"
                "hostname" "foo3"
                "kernel" "Darwin"
                "operatingsystem" "Darwin"
                "memorysize" "16.00 GB"}]
    (testing "should return an empty list if there are no facts"
      (let [{:keys [status body]} (query-response method endpoint)
            result (parse-result body)]
        (is (= status http/status-ok))
        (is (empty? result))))

    (with-transacted-connection fixt/*db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-facts! {:certname "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/deactivate-node! "foo1")
      (scf-store/add-facts! {:certname "foo1"
                             :values  facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)}))

    (let [expected-result ["domain" "hostname" "kernel" "memorysize" "operatingsystem" "uptime_seconds"]]
      (testing "should retrieve all fact names, order alphabetically, including deactivated nodes"
        (let [{:keys [status body]} (query-response method endpoint)
              result (vec (parse-result body))]
          (is (= status http/status-ok))
          (is (= result expected-result))))

      (testing "should retrieve all fact names, ordered reverse-alphabetically,
                including deactivated nodes"
        (let [{:keys [status body]} (query-response
                                      method endpoint nil
                                      {:order_by (order-param
                                                   method
                                                   [{:field "name" :order "desc"}])})
              result (vec (parse-result body))]
          (is (= status http/status-ok))
          (is (= result (reverse expected-result)))))

      (testing "order by rejects invalid fields"
        (let [{:keys [status body]} (query-response
                                      method endpoint nil
                                      {:order_by (order-param
                                                   method [{:field "invalid"
                                                            :order "desc"}])})
              result (parse-result body)]
          (is (= result
                "Unrecognized column 'invalid' specified in :order_by; Supported columns are 'name'"))))

      (testing "offset works"
        (let [{:keys [status body]} (query-response method endpoint nil {:offset 1})
              result (parse-result body)]
          (is (= result (rest expected-result)))))

      (testing "limit works"
        (let [{:keys [status body]} (query-response
                                      method endpoint nil
                                      {:limit 1})
              result (parse-result body)]
          (is (= result [(first expected-result)])))))))

(deftestseq fact-paths-endpoint-tests
  [[version endpoint] fact-path-endpoints
   method [:get :post]]

  (let [facts1 {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "operatingsystem" "Debian"
                "uptime_seconds" "4000"}
        facts2 {"domain" "testing.com"
                "hostname" "foo2"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" "6000"}
        facts3 {"domain" "testing.com"
                "hostname" "foo3"
                "kernel" "Darwin"
                "operatingsystem" "Darwin"
                "memorysize" "16.00 GB"
                "my_SF" {"foo" "bar" "baz" [3.14 2.71]}}
        expected [{:path ["domain"] :type "string"}
                  {:path ["hostname"] :type "string"}
                  {:path ["kernel"] :type "string"}
                  {:path ["memorysize"] :type "string"}
                  {:path ["my_SF" "baz" 0] :type "float"}
                  {:path ["my_SF" "baz" 1] :type "float"}
                  {:path ["my_SF" "foo"] :type "string"}
                  {:path ["operatingsystem"] :type "string"}
                  {:path ["uptime_seconds"] :type "string"}]]

    (testing "should return an empty list if there are no facts"
      (let [{:keys [status body]} (query-response method endpoint)
            result (parse-result body)]
        (is (= status http/status-ok))
        (is (empty? result))))

    (with-transacted-connection fixt/*db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-facts! {:certname "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/deactivate-node! "foo1")
      (scf-store/add-facts! {:certname "foo1"
                             :values  facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)}))

    (testing "query should return appropriate results"
      (let [{:keys [status body]} (query-response
                                    method
                                    endpoint nil
                                    {:order_by (order-param
                                                 method
                                                 [{:field "path" :order "asc"}])})
            result (parse-result body)]
        (is (= status http/status-ok))
        (is (= result expected))))

    (testing "regex operator on path"
      (let [{:keys [status body]} (query-response
                                    method
                                    endpoint
                                    ["~" "path" "my"]
                                    {:order_by (order-param
                                                 method
                                                 [{:field "path"}])})
            result (parse-result body)]
        (is (= status http/status-ok))
        (is (= result
               [{:path ["my_SF" "baz" 0], :type "float"}
                {:path ["my_SF" "baz" 1], :type "float"}
                {:path ["my_SF" "foo"], :type "string"}]))))
    (testing "paging for fact-paths"
      (let [{:keys [status body]} (query-response
                                    method endpoint nil
                                    {:order_by (order-param
                                                 method
                                                 [{:field "path" :order "desc"}])
                                     :offset 2})
            result (parse-result body)]
        (is (= status http/status-ok))
        (is (= result
               [{:path ["my_SF" "foo"], :type "string"}
                {:path ["my_SF" "baz" 1], :type "float"}
                {:path ["my_SF" "baz" 0], :type "float"}
                {:path ["memorysize"], :type "string"}
                {:path ["kernel"], :type "string"}
                {:path ["hostname"], :type "string"}
                {:path ["domain"], :type "string"}]))))
    (testing "invalid query throws an error"
      (let [{:keys [status body]} (query-response
                                    method endpoint ["=" "myfield" "myval"])
            result (parse-result body)]
        (is (= status http/status-bad-request))
        (is (re-find #"is not a queryable object" result))))))
