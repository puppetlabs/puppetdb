(ns com.puppetlabs.puppetdb.test.http.v4.reports
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.reports :as report]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.puppetdb.examples.reports :refer [reports]])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures
        [com.puppetlabs.puppetdb.testutils :only (response-equal? assert-success! get-request paged-results)]
        [com.puppetlabs.puppetdb.testutils.reports :only [store-example-report!]]
        [clj-time.coerce :only [to-date-time to-string]]
        [clj-time.core :only [now]]))

(def endpoint "/v4/reports")

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([query] (get-response endpoint query))
  ([endpoint query] (*app* (get-request endpoint query))))

(defn report-response
 [report]
  (kitchensink/mapvals
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
      (let [result (get-response endpoint ["=" "certname" (:certname basic)])]
        (is (every? #(= "DEV" (:environment %)) (json/parse-string (:body result) true)))
        (is (every? #(= "unchanged" (:status %)) (json/parse-string (:body result) true)))
        (response-equal?
         result
         (reports-response [(assoc basic :hash report-hash)])
         remove-receive-times)))

    (testing "should return all reports for a hash"
      (response-equal?
        (get-response ["=" "hash" report-hash])
        (reports-response [(assoc basic :hash report-hash)])
        remove-receive-times))))

(deftest query-by-status
  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        hash2 (:hash (store-example-report! basic2 (now)))
        basic3 (assoc (:basic3 reports) :status "changed")
        hash3 (:hash (store-example-report! basic3 (now)))
        basic4 (assoc (:basic4 reports) :status "failed")
        hash4 (:hash (store-example-report! basic4 (now)))]

    (testing "should return all reports for a certname"
      (let [unchanged-result (get-response endpoint ["=" "status" "unchanged"])
            unchanged-reports (json/parse-string (:body unchanged-result) true)
            changed-result (get-response endpoint ["=" "status" "changed"])
            changed-reports (json/parse-string (:body changed-result) true)
            failed-result (get-response endpoint ["=" "status" "failed"])
            failed-reports (json/parse-string (:body failed-result) true)]

        (is (= 2 (count unchanged-reports)))
        (is (every? #(= "unchanged" (:status %)) unchanged-reports))

        (response-equal?
         unchanged-result
         (reports-response [(assoc basic :hash hash1)
                            (assoc basic2 :hash hash2)])
         remove-receive-times)

        (is (= 1 (count changed-reports)))
        (response-equal?
         changed-result
         (reports-response [(assoc basic3 :hash hash3)])
         remove-receive-times)

        (is (= 1 (count failed-reports)))
        (response-equal?
         failed-result
         (reports-response [(assoc basic4 :hash hash4)])
         remove-receive-times)))))

(deftest query-by-certname-with-environment
  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))]

    (testing "should return all reports for a certname"
      (let [result (get-response "/v4/environments/DEV/reports" ["=" "certname" (:certname basic)])]
        (is (every? #(= "DEV" (:environment %)) (json/parse-string (:body result) true)))
        (response-equal?
         result
         (reports-response [(assoc basic :hash report-hash)])
         remove-receive-times)))
    (testing "PROD environment"
      (is (empty? (json/parse-string (:body (get-response "/v4/environments/PROD/reports" ["=" "certname" (:certname basic)]))))))))

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
                              :path    endpoint
                              :query   ["=" "certname" (:certname basic1)]
                              :limit   1
                              :total   2
                              :include-total  count?})]
          (is (= 2 (count results)))
          (is (= (reports-response
                  [(assoc basic1 :hash basic1-hash)
                   (assoc basic2 :hash basic2-hash)])
                (set (remove-receive-times results)))))))))

(deftest invalid-queries
  (let [response (get-response ["<" "timestamp" 0])]
    (is (re-matches #".*query operator '<' is unknown" (:body response)))
    (is (= 400 (:status response))))
  (let [response (get-response ["=" "timestamp" 0])]
    (is (re-find #"'timestamp' is not a valid query term" (:body response)))
    (is (= 400 (:status response)))))
