(ns com.puppetlabs.puppetdb.test.http.v2.report
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.report
        [com.puppetlabs.puppetdb.testutils :only (response-equal?)]
        [clj-time.coerce :only [to-date-time to-string]]
        [clj-time.core :only [now]]))


(use-fixtures :each with-test-db with-http-app)

(def content-type-json "application/json")

;; TODO: this might be able to be abstracted out and consolidated with the similar
;; versions that currently reside in test.http.resource and test.http.event
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
  [query report-id] (*app* (get-request "/v2/reports" query report-id)))

(defn report-response
  [report]
  (utils/mapvals
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    to-string
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example report object before comparison
    (dissoc report :resource-events)
    [:start-time :end-time]))

(defn reports-response
  [reports]
  (set (map report-response reports)))

(defn remove-receive-times
  [reports]
  ;; the example reports don't have a receive time (because this is
  ;; calculated by the server), so we remove this field from the response
  ;; for test comparison
  (map #(dissoc % :receive-time) reports))

(deftest query-by-report
  (let [basic (assoc-in (:basic reports) [:id] (utils/uuid))]
    (report/validate basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic (now))

    ;; TODO: test invalid requests

    (testing "should return all reports if no params are passed"
      (response-equal?
        (get-response nil (:id basic))
        (reports-response [basic])
        remove-receive-times))))
