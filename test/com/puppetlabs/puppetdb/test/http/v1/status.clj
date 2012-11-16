(ns com.puppetlabs.puppetdb.test.http.v1.status
  (:require [cheshire.core :as json]
            ring.middleware.params
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-date-time]]
        [clj-time.format :only [parse]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]))

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")
(def v1-url "/v1/status/nodes/")

(defn get-request
  [path]
  (let [request (request :get path)]
    (update-in request [:headers] assoc "Accept" c-t)))

(defn get-response
  ([url]      (get-response url nil))
  ([url node] (*app* (get-request (str url node)))))

;; This is silly, but extracting all of the tests into a function
;; allows us to re-use them for testing the v2 endpoint
(defn test-v1-node-status [url]
  (let [catalog   (:basic catalogs)
        certname  (:certname catalog)
        timestamp (now)]
    (scf-store/add-certname! certname)
    (scf-store/replace-catalog! catalog timestamp)
    (scf-store/add-facts! certname {} timestamp)

    (testing "should be active, and have catalog and facts timestamp if active with catalog+facts"
      (let [response (get-response url certname)
            status   (json/parse-string (:body response) true)]
        (is (= pl-http/status-ok (:status response)))

        (is (= certname (:name status)))
        (is (nil? (:deactivated status)))
        (is (= timestamp (to-date-time (:catalog_timestamp status))))
        (is (= timestamp (to-date-time (:facts_timestamp status))))))

    (scf-store/deactivate-node! certname)
    (scf-store/dissociate-all-catalogs-for-certname! certname)
    (scf-store/delete-facts! certname)

    (testing "should be deactivated, with null timestamps if deactivated with no data"
      (let [response (get-response url certname)
            status   (json/parse-string (:body response) true)]
        (is (= pl-http/status-ok (:status response)))

        (is (= certname (:name status)))
        (is (instance? org.joda.time.DateTime (parse (:deactivated status))))
        (is (nil? (:catalog_timestamp status)))
        (is (nil? (:facts_timestamp status)))))

    (testing "should return status-not-found for an unknown node"
      (let [response (get-response url "unknown-node")
            result (json/parse-string (:body response) true)]
        (is (= pl-http/status-not-found (:status response)))

        (is (= "No information is known about unknown-node" (:error result)))))))

(deftest node-status
  (test-v1-node-status v1-url))
