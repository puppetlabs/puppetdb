(ns com.puppetlabs.puppetdb.test.http.experimental.report
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
  [path query]
  (let [request (request :get path
                     {"query" (if (string? query) query (json/generate-string query))})
        headers (:headers request)]
    (assoc request :headers (assoc headers "Accept" content-type-json))))

(defn get-response
  [query] (*app* (get-request "/experimental/reports" query)))

(defn report-response
  [report]
  (utils/mapvals
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    to-string
    [:start-time :end-time]
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example report object before comparison
    (dissoc report :resource-events)))

(defn reports-response
  [reports]
  (set (map report-response reports)))

(defn remove-receive-times
  [reports]
  ;; the example reports don't have a receive time (because this is
  ;; calculated by the server), so we remove this field from the response
  ;; for test comparison
  (map #(dissoc % :receive-time) reports))

(deftest query-by-certname
  (let [basic         (:basic reports)
        report-hash   (scf-store/report-identity-string basic)]
    (report/validate! basic)
    (scf-store/add-certname! (:certname basic))
    (scf-store/add-report! basic (now))

    ;; TODO: test invalid requests

    (testing "should return all reports for a certname"
      (response-equal?
        (get-response ["=" "certname" (:certname basic)])
        (reports-response [(assoc basic :hash report-hash)])
        remove-receive-times))))
