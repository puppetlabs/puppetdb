(ns com.puppetlabs.puppetdb.test.http.v1.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.testutils :only [get-request assert-success!]]
        [com.puppetlabs.jdbc :only [with-transacted-connection]]))

(def endpoint "/v1/facts")

(use-fixtures :each with-test-db with-http-app)

(def c-t pl-http/json-response-content-type)

(deftest fact-set-handler
  (let [certname_with_facts "got_facts"
        certname_without_facts "no_facts"
        facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! certname_without_facts)
      (scf-store/add-certname! certname_with_facts)
      (scf-store/add-facts! certname_with_facts facts (now)))

    (testing "for an absent node"
      (let [request (get-request (str endpoint "/imaginary_node"))
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response) true)
               {:error "Could not find facts for imaginary_node"}))))

    (testing "for a present node without facts"
      (let [request (get-request (str endpoint "/" certname_without_facts))
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response) true)
               {:error (str "Could not find facts for " certname_without_facts)}))))

    (testing "for a present node with facts"
      (let [request (get-request (str endpoint "/" certname_with_facts))
            response (*app* request)]
        (assert-success! response)
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response))
               {"name" certname_with_facts "facts" facts}))))))
