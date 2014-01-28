(ns com.puppetlabs.puppetdb.test.http.v2.fact-names
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.testutils :only [get-request]]
        [com.puppetlabs.jdbc :only [with-transacted-connection]]))

(def endpoint "/v2/fact-names")

(use-fixtures :each with-test-db with-http-app)

(deftest all-fact-names
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
            {:keys [status body]} (*app* request)
            result (json/parse-string body)]
        (is (= status pl-http/status-ok))
        (is (empty? []))))

    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-facts! "foo1" facts1 (now))
      (scf-store/add-facts! "foo2" facts2 (now))
      (scf-store/add-facts! "foo3" facts3 (now))
      (scf-store/deactivate-node! "foo1"))

    (testing "should retrieve all fact names, order alphabetically, including deactivated nodes"
      (let [request (get-request endpoint)
            {:keys [status body]} (*app* request)
            result (json/parse-string body)]
        (is (= status pl-http/status-ok))
        (is (= result ["domain" "hostname" "kernel" "memorysize" "operatingsystem" "uptime_seconds"]))))

    (testing "should not support paging-related query parameters"
      (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
        (let [request (get-request endpoint nil {k v})
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body (format "Unsupported query parameter '%s'" (name k)))))))))
