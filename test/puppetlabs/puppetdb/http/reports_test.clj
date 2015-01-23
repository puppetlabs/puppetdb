(ns puppetlabs.puppetdb.http.reports-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.http :as http :refer [status-bad-request]]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.testutils :refer [response-equal?
                                                   assert-success!
                                                   get-request
                                                   paged-results
                                                   deftestseq]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!
                                                           munge-resource-events]]
            [flatland.ordered.map :as omap]
            [clj-time.coerce :refer [to-date-time to-string]]
            [clj-time.core :refer [now]]
            [clojure.string :as str]
            [clj-time.format :as tfmt]
            [clj-time.coerce :as tcoerce]))

(def endpoints [[:v4 "/v4/reports"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(defn get-response
  [endpoint query]
  (let [resp (fixt/*app* (get-request endpoint query))]
    (if (string? (:body resp))
      resp
      (update-in resp [:body] slurp))))

(defn report-response
  [report]
  (kitchensink/mapvals
   ;; the timestamps are already strings, but calling to-string on them forces
   ;; them to be coerced to dates and then back to strings, which normalizes
   ;; the timezone so that it will match the value returned form the db.
   to-string
   [:start_time :end_time]
   ;; the response won't include individual events, so we need to pluck those
   ;; out of the example report object before comparison
   (update-in report [:resource_events] (comp keywordize-keys munge-resource-events))))

(defn reports-response
  [version reports]
  (set (map report-response reports)))

(defn munge-reports-for-comparison
  [reports]
  ;; the example reports don't have a receive time (because this is
  ;; calculated by the server), so we remove this field from the response
  ;; for test comparison
  (map (comp #(dissoc % :receive_time) #(update-in % [:resource_events] set)) reports))

(deftestseq query-by-certname
  [[version endpoint] endpoints]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))
        basic (assoc basic :hash report-hash)]

    (doseq [field ["certname" "hash" "puppet_version" "report_format"
                   "configuration_version" "start_time" "end_time"
                   "transaction_uuid" "status"]
            :let [field-kwd (keyword field)]]
      (testing (format "should return all reports for a %s" field)
        (let [result (get-response endpoint ["=" field (get basic field-kwd)])]
          (is (every? #(= "DEV" (:environment %)) (json/parse-string (:body result) true)))
          (is (every? #(= "unchanged" (:status %)) (json/parse-string (:body result) true)))
          (response-equal?
           result
           (reports-response version [basic])
           munge-reports-for-comparison))))))

(deftestseq query-with-projection
  [[version endpoint] endpoints]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))
        bar-report-hash (:hash (store-example-report! (assoc basic :certname "bar.local") (now)))
        basic (assoc basic :hash report-hash)]

    (testing "one projected column"
      (response-equal?
       (get-response endpoint ["extract" "hash"
                               ["=" "certname" (:certname basic)]])
       #{{:hash report-hash}}))

    (testing "one projected column with a not"
      (response-equal?
       (get-response endpoint ["extract" "hash"
                               ["not" ["=" "certname" (:certname basic)]]])
       #{{:hash bar-report-hash}}))

    (testing "three projected columns"
      (response-equal?
       (get-response endpoint ["extract" ["hash" "certname" "transaction_uuid"]
                               ["=" "certname" (:certname basic)]])
       #{(select-keys basic [:hash :certname :transaction_uuid])}))))

(deftestseq query-with-paging
  [[version endpoint] endpoints]

  (let [basic1        (:basic reports)
        basic1-hash   (:hash (store-example-report! basic1 (now)))
        basic2        (:basic2 reports)
        basic2-hash   (:hash (store-example-report! basic2 (now)))]

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through reports " label " counts")
        (let [results       (paged-results
                             {:app-fn  fixt/*app*
                              :path    endpoint
                              :query   ["=" "certname" (:certname basic1)]
                              :limit   1
                              :total   2
                              :include_total  count?})]
          (is (= 2 (count results)))
          (is (= (reports-response version
                                   [(assoc basic1 :hash basic1-hash)
                                    (assoc basic2 :hash basic2-hash)])
                 (set (munge-reports-for-comparison results)))))))))

(deftestseq invalid-queries
  [[version endpoint] endpoints]

  (let [response (get-response endpoint ["<" "environment" 0])]
    (is (re-matches #".*Query operators .*<.* not allowed .* environment" (:body response)))
    (is (= 400 (:status response))))

  (let [response (get-response endpoint ["=" "timestamp" 0])]
    (is (re-find #"'timestamp' is not a queryable object for reports" (:body response)))
    (is (= 400 (:status response)))))

(deftestseq query-by-status
  [[version endpoint] endpoints]

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
         (reports-response version [(assoc basic :hash hash1)
                                    (assoc basic2 :hash hash2)])
         munge-reports-for-comparison)

        (is (= 1 (count changed-reports)))
        (response-equal?
         changed-result
         (reports-response version [(assoc basic3 :hash hash3)])
         munge-reports-for-comparison)

        (is (= 1 (count failed-reports)))
        (response-equal?
         failed-result
         (reports-response version [(assoc basic4 :hash hash4)])
         munge-reports-for-comparison)))))

(deftestseq query-by-certname-with-environment
  [[version endpoint] endpoints]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))]

    (testing "should return all reports for a certname"
      (let [result (get-response "/v4/environments/DEV/reports" ["=" "certname" (:certname basic)])]
        (is (every? #(= "DEV" (:environment %)) (json/parse-string (:body result) true)))
        (response-equal?
         result
         (reports-response version [(assoc basic :hash report-hash)])
         munge-reports-for-comparison)))
    (testing "PROD environment"
      (is (empty? (json/parse-string
                   (:body
                    (get-response "/v4/environments/PROD/reports"
                                  ["=" "certname" (:certname basic)]))))))))

(deftestseq query-by-puppet-version
  [[version endpoint] endpoints]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (assoc (:basic2 reports) :puppet_version "3.6.0")
        hash2 (:hash (store-example-report! basic2 (now)))
        basic3 (assoc (:basic3 reports) :puppet_version "3.0.3")
        hash3 (:hash (store-example-report! basic3 (now)))

        v301 (get-response endpoint ["=" "puppet_version" "3.0.1"])
        v301-body (json/parse-strict-string (:body v301) true)
        v360 (get-response endpoint ["=" "puppet_version" "3.6.0"])
        v360-body (json/parse-strict-string (:body v360) true)
        v30x (get-response endpoint ["~" "puppet_version" "3\\.0\\..*"])
        v30x-body (json/parse-strict-string (:body v30x) true)]

    (is (= 1 (count v301-body)))
    (response-equal?
     v301
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)

    (is (= 1 (count v360-body)))
    (response-equal?
     v360
     (reports-response version [(assoc basic2 :hash hash2)])
     munge-reports-for-comparison)

    (is (= 2 (count v30x-body)))
    (response-equal?
     v30x
     (reports-response version [(assoc basic :hash hash1)
                                (assoc basic3 :hash hash3)])
     munge-reports-for-comparison)))

(deftestseq query-by-report-format
  [[version endpoint] endpoints]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (assoc (:basic2 reports) :report_format 5)
        hash2 (:hash (store-example-report! basic2 (now)))
        basic3 (assoc (:basic3 reports) :report_format 6)
        hash3 (:hash (store-example-report! basic3 (now)))

        v4-format (get-response endpoint ["=" "report_format" 4])
        v4-format-body (json/parse-strict-string (:body v4-format) true)
        v5-format (get-response endpoint ["and"
                                          [">" "report_format" 4]
                                          ["<" "report_format" 6]])
        v5-format-body (json/parse-strict-string (:body v5-format) true)
        v6-format (get-response endpoint ["and"
                                          [">" "report_format" 4]
                                          ["<=" "report_format" 6]])
        v6-format-body (json/parse-strict-string (:body v6-format) true)]

    (is (= 1 (count v4-format-body)))
    (response-equal?
     v4-format
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)

    (is (= 1 (count v5-format-body)))
    (response-equal?
     v5-format
     (reports-response version [(assoc basic2 :hash hash2)])
     munge-reports-for-comparison)

    (is (= 2 (count v6-format-body)))
    (response-equal?
     v6-format
     (reports-response version [(assoc basic2 :hash hash2)
                                (assoc basic3 :hash hash3)])
     munge-reports-for-comparison)))

(deftestseq query-by-configuration-version
  [[version endpoint] endpoints]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        hash2 (:hash (store-example-report! basic2 (now)))

        basic-result (get-response endpoint ["=" "configuration_version" "a81jasj123"])
        basic-result-body (json/parse-strict-string (:body basic-result) true)
        basic2-result (get-response endpoint ["~" "configuration_version" ".*23"])
        basic2-result-body (json/parse-strict-string (:body basic2-result) true)]

    (is (= 1 (count basic-result-body)))
    (response-equal?
     basic-result
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)

    (is (= 2 (count basic2-result-body)))
    (response-equal?
     basic2-result
     (reports-response version [(assoc basic :hash hash1)
                                (assoc basic2 :hash hash2)])
     munge-reports-for-comparison)))

(deftestseq query-by-start-and-end-time
  [[version endpoint] endpoints]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        hash2 (:hash (store-example-report! basic2 (now)))

        basic-result (get-response endpoint ["=" "start_time" "2011-01-01T12:00:00-03:00"])
        basic-result-body (json/parse-strict-string (:body basic-result) true)
        basic-range (get-response endpoint ["and"
                                            [">" "start_time" "2010-01-01T12:00:00-03:00"]
                                            ["<" "end_time" "2012-01-01T12:00:00-03:00"]])
        basic-range-body (json/parse-strict-string (:body basic-range) true)
        all-reports (get-response endpoint ["and"
                                            [">" "start_time" "2010-01-01T12:00:00-03:00"]
                                            ["<" "end_time" "2014-01-01T12:00:00-03:00"]])
        all-reports-body (json/parse-strict-string (:body all-reports) true)
        basic2-result (get-response endpoint ["=" "end_time" "2013-08-28T19:10:00-03:00"])
        basic2-result-body (json/parse-strict-string (:body basic2-result) true)]

    (is (= 1 (count basic-result-body)))
    (response-equal?
     basic-result
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)

    (is (= 1 (count basic-range-body)))
    (response-equal?
     basic-range
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)

    (is (= 1 (count basic2-result-body)))
    (response-equal?
     basic2-result
     (reports-response version [(assoc basic2 :hash hash2)])
     munge-reports-for-comparison)

    (is (= 2 (count all-reports-body)))
    (response-equal?
     all-reports
     (reports-response version [(assoc basic :hash hash1)
                                (assoc basic2 :hash hash2)])
     munge-reports-for-comparison)))

(defn ts->str [ts]
  (tfmt/unparse (tfmt/formatters :date-time) (tcoerce/to-date-time ts)))

(deftestseq query-by-receive-time
  [[version endpoint] endpoints]

  (let [basic (:basic reports)
        stored-basic (store-example-report! basic (now))
        hash1 (:hash stored-basic)

        basic-result (get-response endpoint ["=" "receive_time" (ts->str (:receive_time stored-basic))])
        basic-result-body (json/parse-strict-string (:body basic-result) true)]

    (is (= 1 (count basic-result-body)))
    (response-equal?
     basic-result
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)))

(deftestseq query-by-transaction-uuid
  [[version endpoint] endpoints]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        hash2 (:hash (store-example-report! basic2 (now)))

        basic-result (get-response endpoint ["=" "transaction_uuid" "68b08e2a-eeb1-4322-b241-bfdf151d294b"])
        basic-result-body (json/parse-strict-string (:body basic-result) true)
        all-results (get-response endpoint ["~" "transaction_uuid" "b$"])
        all-results-body (json/parse-strict-string (:body all-results) true)]

    (is (= 1 (count basic-result-body)))
    (response-equal?
     basic-result
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)

    (is (= 2 (count all-results-body)))
    (response-equal?
     all-results
     (reports-response version [(assoc basic :hash hash1)
                                (assoc basic2 :hash hash2)])
     munge-reports-for-comparison)))

(deftestseq query-by-hash
  [[version endpoint] endpoints]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        hash2 (:hash (store-example-report! basic2 (now)))

        basic-result (get-response endpoint ["=" "hash" hash1])
        basic-result-body (json/parse-strict-string (:body basic-result) true)]

    (is (= 1 (count basic-result-body)))
    (response-equal?
     basic-result
     (reports-response version [(assoc basic :hash hash1)])
     munge-reports-for-comparison)))

(def invalid-projection-queries
  (omap/ordered-map
    ;; Top level extract using invalid fields should throw an error
    ["extract" "nothing" ["~" "certname" ".*"]]
    #"Can't extract unknown 'reports' field 'nothing'.*Acceptable fields are.*"

    ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
    #"Can't extract unknown 'reports' fields: 'nothing', 'nothing2'.*Acceptable fields are.*"))

(deftestseq invalid-projections
  [[version endpoint] endpoints]

  (doseq [[query msg] invalid-projection-queries]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (get-response endpoint query)]
        (is (re-find msg body))
        (is (= status status-bad-request))))))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
    "/v4/reports" (omap/ordered-map
                  ["~" "certname" "*abc"]
                  #".*invalid regular expression: quantifier operand invalid"

                  ["~" "certname" "[]"]
                  #".*invalid regular expression: brackets.*not balanced")))

(deftestseq ^{:hsqldb false} pg-invalid-regexps
  [[version endpoint] endpoints]

  (doseq [[query msg] (get pg-versioned-invalid-regexps endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (get-response endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))
