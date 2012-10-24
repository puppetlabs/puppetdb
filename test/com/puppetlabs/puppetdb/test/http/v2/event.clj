(ns com.puppetlabs.puppetdb.test.http.v2.event
  (:require [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json])
  (:use clojure.test
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
  [path query report-id]
    ;; TODO: clean this up... gotta be a better way :)
    (let [query-arg     (if query
                          {"query" (if (string? query) query (json/generate-string query))}
                          {})
          report-id-arg  (if report-id
                          {"report-id" report-id}
                          {})
          request (request :get path (merge query-arg report-id-arg))
          headers (:headers request)]
      (assoc request :headers (assoc headers "Accept" content-type-json))))

(defn get-response
  [query report-id] (*app* (get-request "/v2/events" query report-id)))


(defn resource-event-response
  [resource-event report-id]
  (-> resource-event
    (assoc-in [:report-id] report-id)
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    (update-in [:timestamp] to-string)))

(defn resource-events-response
  [resource-events report-id]
  (set (map #(resource-event-response % report-id) resource-events)))


(deftest query-by-report
  (let [report-id (utils/uuid)
        basic (assoc-in (:basic reports) [:id] report-id)]
    (report/validate basic)
    (scf-store/add-report! basic (now))

    ;; TODO: test invalid requests

    (testing "should return the list of resource events for a given report id"
      (response-equal?
        (get-response nil (:id basic))
        (resource-events-response (:resource-events basic) report-id)))))
