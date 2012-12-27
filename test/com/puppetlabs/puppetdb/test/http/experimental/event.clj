(ns com.puppetlabs.puppetdb.test.http.experimental.event
  (:require [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json])
  (:use clojure.test
        [clojure.walk :only [stringify-keys]]
        ring.mock.request
        com.puppetlabs.puppetdb.examples.report
        com.puppetlabs.puppetdb.fixtures
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-string]]
        [com.puppetlabs.puppetdb.testutils :only (response-equal?)]))

(def content-type-json "application/json")

(use-fixtures :each with-test-db with-http-app)

;; TODO: these might be able to be abstracted out and consolidated with the similar version
;; that currently resides in test.http.resource
(defn get-request
  [path query]
    (let [request (request :get path
                      {"query" (if (string? query) query (json/generate-string query))})
          headers (:headers request)]
      (assoc request :headers (assoc headers "Accept" content-type-json))))

(defn get-response
  [query] (*app* (get-request "/experimental/events" query)))


(defn expected-resource-event-response
  [resource-event report-hash]
  (-> resource-event
    ;; the examples don't include the report hash, so we munge it into place
    (assoc-in [:report] report-hash)
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    (update-in [:timestamp] to-string)))

(defn expected-resource-events-response
  [resource-events report-hash]
  (set (map #(expected-resource-event-response % report-hash) resource-events)))

(deftest query-by-report
  (let [basic       (:basic reports)
        report-hash (scf-store/report-identity-string basic)]
    (report/validate! basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic (now))

    ;; TODO: test invalid requests

    (testing "should return the list of resource events for a given report hash"
      (let [response (get-response ["=" "report" report-hash])
            expected (expected-resource-events-response
                        (:resource-events basic)
                        report-hash)
            munge-event-values #(utils/maptrans {[:old-value :new-value] stringify-keys} %)]
        (response-equal? response expected #(map munge-event-values %))))))
