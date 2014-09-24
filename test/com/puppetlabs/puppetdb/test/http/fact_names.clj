(ns com.puppetlabs.puppetdb.test.http.fact-names
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [clj-time.core :refer [now]]
            [com.puppetlabs.puppetdb.testutils :refer [paged-results get-request
                                                       deftestseq parse-result]]
            [com.puppetlabs.jdbc :refer [with-transacted-connection]]))

(def fact-name-endpoints [[:v3 "/v3/fact-names"]
                          [:v4 "/v4/fact-names"]])

(def fact-path-endpoints [[:v4 "/v4/fact-paths"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(deftest fact-names-legacy-paging-should-fail
  (testing "should not support paging-related query parameters for :v2"
    (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
      (let [request (get-request "/v2/fact-names" nil {k v})
            {:keys [status body]} (fixt/*app* request)]
        (is (= status pl-http/status-bad-request))
        (is (= body (format "Unsupported query parameter '%s'" (name k))))))))

(deftestseq fact-names-endpoint-tests
  [[version endpoint] fact-name-endpoints]

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
      (let [request (get-request endpoint)
            {:keys [status body]} (fixt/*app* request)
            result (json/parse-string body)]
        (is (= status pl-http/status-ok))
        (is (empty? result))))

    (with-transacted-connection fixt/*db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-facts! {:name "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name "foo3"
                             :values facts3
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/deactivate-node! "foo1")
      (scf-store/add-facts! {:name "foo1"
                             :values  facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil}))

    (testing "should retrieve all fact names, order alphabetically, including deactivated nodes"
      (let [request (get-request endpoint)
            {:keys [status body]} (fixt/*app* request)
            result (json/parse-string body)]
        (is (= status pl-http/status-ok))
        (is (= result ["domain" "hostname" "kernel" "memorysize" "operatingsystem" "uptime_seconds"]))))))

(deftestseq fact-paths-endpoint-tests
  [[version endpoint] fact-path-endpoints]

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
      (let [request (get-request endpoint)
            {:keys [status body]} (fixt/*app* request)
            result (parse-result body)]
        (is (= status pl-http/status-ok))
        (is (empty? result))))

    (with-transacted-connection fixt/*db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-facts! {:name "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name "foo3"
                             :values facts3
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/deactivate-node! "foo1")
      (scf-store/add-facts! {:name "foo1"
                             :values  facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil}))

    (testing "query should return appropriate results"
      (let [request (get-request
                     endpoint nil
                     {:order-by (json/generate-string [{:field "path" :order "asc"}])})
            {:keys [status body]} (fixt/*app* request)
            result (parse-result body)]
        (is (= status pl-http/status-ok))
        (is (= result expected))))

    (testing "regex operator on path"
      (let [request (get-request
                     endpoint (json/generate-string ["~" "path" "my"])
                     {:order-by (json/generate-string [{:field "path"}])})
            {:keys [status body]} (fixt/*app* request)
            result (parse-result body)]
        (is (= status pl-http/status-ok))
        (is (= result
               [{:path ["my_SF" "baz" 0], :type "float"}
                {:path ["my_SF" "baz" 1], :type "float"}
                {:path ["my_SF" "foo"], :type "string"}]))))
    (testing "paging for fact-paths"
      (let [request (get-request endpoint nil
                                 {:order-by (json/generate-string [{:field "path" :order "desc"}])
                                  :offset 2})
            {:keys [status body]} (fixt/*app* request)
            result (parse-result body)]
        (is (= status pl-http/status-ok))
        (is (= result
               [{:path ["my_SF" "foo"], :type "string"}
                {:path ["my_SF" "baz" 1], :type "float"}
                {:path ["my_SF" "baz" 0], :type "float"}
                {:path ["memorysize"], :type "string"}
                {:path ["kernel"], :type "string"}
                {:path ["hostname"], :type "string"}
                {:path ["domain"], :type "string"}]))))
    (testing "invalid query throws an error"
      (let [request (get-request endpoint (json/generate-string
                                           ["=" "myfield" "myval"]))
            {:keys [status body]} (fixt/*app* request)
            result (parse-result body)]
        (is (= status pl-http/status-bad-request))
        (is (re-find #"is not a queryable object" result))))))
