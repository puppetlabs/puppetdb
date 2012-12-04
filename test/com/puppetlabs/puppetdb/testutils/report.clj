(ns com.puppetlabs.puppetdb.testutils.report
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.report :as report]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.puppetdb.query.report :as query])
  (:use [clj-time.coerce :only [to-timestamp]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn store-report!
  [example-report timestamp]
  (let [report-hash   (scf-store/report-identity-string example-report)]
    (report/validate! example-report)
    (scf-store/maybe-activate-node! (:certname example-report) timestamp)
    (scf-store/add-report! example-report timestamp)
    report-hash))

(defn expected-report
  [example-report]
  (utils/mapvals
    ;; we need to map the datetime fields to timestamp objects for comparison
    to-timestamp
    [:start-time :end-time]
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example report object before comparison
    (dissoc example-report :resource-events)))

(defn expected-reports
  [example-reports]
  (map expected-report example-reports))

(defn reports-query-result
  [query]
  (vec (->> (query/report-query->sql query)
         (query/query-reports)
         ;; the example reports don't have a receive time (because this is
         ;; calculated by the server), so we remove this field from the response
         ;; for test comparison
         (map #(dissoc % :receive-time)))))
