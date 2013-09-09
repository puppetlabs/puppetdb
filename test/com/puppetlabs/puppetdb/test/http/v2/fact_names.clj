(ns com.puppetlabs.puppetdb.test.http.v2.fact-names
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn make-request
  "Return a GET request against path, suitable as an argument to a ring
  app. Params supported are content-type and query-string."
  ([path] (make-request path {}))
  ([path params]
     (let [request (request :get path params)]
       (update-in request [:headers] assoc "Accept" c-t))))

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
      (let [request (make-request "/v2/fact-names")
            {:keys [status body]} (*app* request)
            result (json/parse-string body)]
        (is (= status pl-http/status-ok))
        (is (= result []))))

    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-facts! "foo1" facts1 (now))
      (scf-store/add-facts! "foo2" facts2 (now))
      (scf-store/add-facts! "foo3" facts3 (now))
      (scf-store/deactivate-node! "foo1"))

    (testing "should retrieve all fact names, order alphabetically, including deactivated nodes"
      (let [request (make-request "/v2/fact-names")
            {:keys [status body]} (*app* request)
            result (json/parse-string body)]
        (is (= status pl-http/status-ok))
        (is (= result ["domain" "hostname" "kernel" "memorysize" "operatingsystem" "uptime_seconds"]))))

    (testing "should not support paging-related query parameters"
      (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
        (let [request (make-request "/v2/fact-names" {k v})
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body (format "Unsupported query parameter '%s'" (name k)))))))))

