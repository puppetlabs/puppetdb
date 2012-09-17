(ns com.puppetlabs.puppetdb.test.http.v1.facts
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
  ([path {keys [:query-string :content-type]
          :or {:query-string "" :content-type c-t} :as params}]
     (let [request (request :get path (:query-string params))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" (:content-type params))))))

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
      (let [request (make-request "/v1/facts/imaginary_node")
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response) true)
               {:error "Could not find facts for imaginary_node"}))))

    (testing "for a present node without facts"
      (let [request (make-request (format "/v1/facts/%s" certname_without_facts))
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response) true)
               {:error (str "Could not find facts for " certname_without_facts)}))))

    (testing "for a present node with facts"
      (let [request (make-request (format "/v1/facts/%s" certname_with_facts))
            response (*app* request)]
        (is (= (:status response) pl-http/status-ok))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response))
               {"name" certname_with_facts "facts" facts}))))))
