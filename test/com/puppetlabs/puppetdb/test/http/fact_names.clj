(ns com.puppetlabs.puppetdb.test.http.fact-names
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [clj-time.core :refer [now]]
            [com.puppetlabs.puppetdb.testutils :refer [paged-results get-request
                                                       deftestseq]]
            [com.puppetlabs.jdbc :refer [with-transacted-connection]]))

(def endpoints [[:v3 "/v3/fact-names"]
                [:v4 "/v4/fact-names"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(deftest fact-names-legacy-paging-should-fail
  (testing "should not support paging-related query parameters for :v2"
    (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
      (let [request (get-request "/v2/fact-names" nil {k v})
            {:keys [status body]} (fixt/*app* request)]
        (is (= status pl-http/status-bad-request))
        (is (= body (format "Unsupported query parameter '%s'" (name k))))))))

(deftestseq fact-names-endpoint-tests
  [[version endpoint] endpoints]

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
      (scf-store/add-facts! "foo2" facts2 (now) "DEV" nil)
      (scf-store/add-facts! "foo3" facts3 (now) "DEV" nil)
      (scf-store/deactivate-node! "foo1")
      (scf-store/add-facts! "foo1" facts1 (now) "DEV" nil))

    (testing "should retrieve all fact names, order alphabetically, including deactivated nodes"
      (let [request (get-request endpoint)
            {:keys [status body]} (fixt/*app* request)
            result (json/parse-string body)]
        (is (= status pl-http/status-ok))
        (is (= result ["domain" "hostname" "kernel" "memorysize" "operatingsystem" "uptime_seconds"]))))))
