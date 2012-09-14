(ns com.puppetlabs.puppetdb.test.http.status
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

(defn get-request
  [path]
  (let [request (request :get path)]
    (update-in request [:headers] assoc "Accept" c-t)))

(defn get-response
  ([]      (get-response nil))
  ([node] (*app* (get-request (str "/status/nodes/" node)))))

(deftest node-status
  (let [catalog   (:basic catalogs)
        certname  (:certname catalog)
        timestamp (now)]
    (scf-store/add-certname! certname)
    (scf-store/store-catalog-for-certname! catalog timestamp)
    (scf-store/add-facts! certname {} timestamp)

    (testing "should be active, and have catalog and facts timestamp if active with catalog+facts"
      (let [response (get-response certname)
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
      (let [response (get-response certname)
            status   (json/parse-string (:body response) true)]
        (is (= pl-http/status-ok (:status response)))

        (is (= certname (:name status)))
        (is (instance? org.joda.time.DateTime (parse (:deactivated status))))
        (is (nil? (:catalog_timestamp status)))
        (is (nil? (:facts_timestamp status)))))

    (testing "should return status-not-found for an unknown node"
      (let [response (get-response "unknown-node")
            result (json/parse-string (:body response) true)]
        (is (= pl-http/status-not-found (:status response)))

        (is (= "No information is known about unknown-node" (:error result)))))))
