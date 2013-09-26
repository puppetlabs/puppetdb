(ns com.puppetlabs.puppetdb.test.http.v3.reports
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.reports :as report]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.reports
        [com.puppetlabs.puppetdb.testutils :only (response-equal? assert-success! get-request paged-results)]
        [com.puppetlabs.puppetdb.testutils.reports :only [store-example-report!]]
        [clj-time.coerce :only [to-date-time to-string]]
        [clj-time.core :only [now]]))


(use-fixtures :each with-test-db with-http-app)

(defn get-response
  [query] (*app* (get-request "/v3/reports" query)))

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
        report-hash   (:hash (store-example-report! basic (now)))]

    ;; TODO: test invalid requests

    (testing "should return all reports for a certname"
      (response-equal?
        (get-response ["=" "certname" (:certname basic)])
        (reports-response [(assoc basic :hash report-hash)])
        remove-receive-times))

    (testing "should return all reports for a hash"
      (response-equal?
        (get-response ["=" "hash" report-hash])
        (reports-response [(assoc basic :hash report-hash)])
        remove-receive-times))))

(deftest query-with-paging
  (let [basic1        (:basic reports)
        basic1-hash   (:hash (store-example-report! basic1 (now)))
        basic2        (:basic2 reports)
        basic2-hash   (:hash (store-example-report! basic2 (now)))]
    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through reports " label " counts")
        (let [results       (paged-results
                              {:app-fn  *app*
                               :path    "/v3/reports"
                               :query   ["=" "certname" (:certname basic1)]
                               :limit   1
                               :total   2
                               :include-total  count?})]
          (is (= 2 (count results)))
          (is (= (reports-response
                    [(assoc basic1 :hash basic1-hash)
                     (assoc basic2 :hash basic2-hash)])
                (set (remove-receive-times results)))))))))
