(ns puppetlabs.puppetdb.http.reports-test
  (:require [clj-time.coerce :refer [to-date-time to-string] :as tcoerce]
            [clj-time.core :refer [now ago days]]
            [clj-time.format :as tfmt]
            [clojure.string :as str]
            [puppetlabs.puppetdb.query-eng :as qe]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [flatland.ordered.map :as omap]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.http :as http :refer [status-bad-request]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils :refer [assert-success!
                                                   get-request
                                                   paged-results
                                                   deftestseq]]
            [puppetlabs.puppetdb.testutils.http :refer [query-response
                                                        query-result
                                                        ordered-query-result
                                                        vector-param]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!
                                                           munge-resource-events]]))

(def endpoints [[:v4 "/v4/reports"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

;; TRANSFORMATIONS

(defn strip-expanded
  "Strips out expanded data from the wire format if the database is HSQLDB"
  [report]
  (if (sutils/postgres?)
    report
    (dissoc report :resource_events :metrics :logs)))

(defn normalize-time
  "Normalize start_time end_time, by coercing it, it forces the timezone to
  become consistent during comparison."
  [record]
  (kitchensink/mapvals
   to-string
   [:start_time :end_time :producer_timestamp]
   record))

(defn munge-actual-report
  [report]
  (-> report
      (update-in [:resource_events] (comp keywordize-keys munge-resource-events))
      normalize-time
      strip-expanded))

(defn munge-expected-report
  [report]
  (-> report
      (update-in [:resource_events] (comp keywordize-keys munge-resource-events))
      normalize-time
      strip-expanded))

(defn munge-actual-reports
  "Convert actual reports to a format ready for comparison"
  [reports]
  (map (comp munge-actual-report
             reports/report-query->wire-v5
             #(update-in % [:logs :data] keywordize-keys)
             #(update-in % [:metrics :data] keywordize-keys))
       reports))

(defn munge-expected-reports
  [reports]
  (map munge-expected-report reports))

(defn report-response
  [report]
  (kitchensink/mapvals
   ;; the timestamps are already strings, but calling to-string on them forces
   ;; them to be coerced to dates and then back to strings, which normalizes
   ;; the timezone so that it will match the value returned form the db.
   to-string
   [:start_time :end_time :producer_timestamp]
   ;; the response won't include individual events, so we need to pluck those
   ;; out of the example report object before comparison
   (-> report
       (update-in [:resource_events] (comp keywordize-keys munge-resource-events))
       (update-in [:metrics] keywordize-keys)
       strip-expanded)))

(defn reports-response
  "Convert expected results to a comparable format"
  [version reports]
  (set (map report-response
            reports)))

(defn munge-report-for-comparison
  [report]
  (-> report
      (update-in [:resource_events] (comp keywordize-keys munge-resource-events))
      strip-expanded))

(defn munge-reports-for-comparison
  "Convert actual results for reports queries to wire format ready for comparison."
  [reports]
  (map munge-report-for-comparison
       (reports/reports-query->wire-v5 reports)))

;; TESTS

(deftestseq query-by-parameters
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))
        basic-with-hash (assoc basic :hash report-hash)]

    (doseq [field ["certname" "hash" "puppet_version" "report_format"
                   "configuration_version" "start_time" "end_time"
                   "transaction_uuid" "status"]
            :let [field-kwd (keyword field)]]
      (testing (format "should return all reports for a %s" field)
        (let [result (query-response method endpoint ["=" field (get basic-with-hash field-kwd)])
              result (-> result
                         :body
                         slurp
                         (json/parse-string true)
                         munge-reports-for-comparison)]
          (is (every? #(= "DEV" (:environment %)) result))
          (is (every? #(= "unchanged" (:status %)) result))
          (is (= (set result) (reports-response version [basic]))))))))

(deftestseq query-with-projection
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))
        bar-report-hash (:hash (store-example-report! (assoc basic :certname "bar.local") (now)))
        basic (assoc basic :hash report-hash)]

    (testing "one projected column"
      (is (= (query-result method endpoint ["extract" "hash"
                                                 ["=" "certname" (:certname basic)]])
             #{{:hash report-hash}})))

    (testing "logs projected"
      (is (= (query-result method endpoint ["extract" "logs"
                                            ["=" "certname" (:certname basic)]])
             #{{:logs (merge {:href (utils/as-path "/v4/reports" report-hash "logs")}
                             (when (sutils/postgres?) {:data (:logs basic)}))}})))

    (testing "metrics projected"
      (is (= (query-result method endpoint ["extract" "metrics"
                                            ["=" "certname" (:certname basic)]])
             #{{:metrics (merge {:href (utils/as-path "/v4/reports" report-hash "metrics")}
                                (when (sutils/postgres?) {:data (:metrics basic)}))}})))

    (testing "one projected column with a not"
      (is (= (query-result method endpoint ["extract" "hash"
                                            ["not" ["=" "certname" (:certname basic)]]])
             #{{:hash bar-report-hash}})))

    (testing "projected aggregate count call"
      (is (= (query-result method endpoint ["extract" [["function" "count"] "status"]
                                            ["~" "certname" ".*"]
                                            ["group_by" "status"]])
             #{{:status "unchanged", :count 2}})))

    (testing "projected aggregate sum call"
      (is (= (query-result method endpoint ["extract"
                                            [["function" "sum" "report_format"] "status"]
                                            ["~" "certname" ".*"]
                                            ["group_by" "status"]])
             #{{:status "unchanged", :sum 8}})))

    (testing "projected aggregate function call with two column groupings"
      (is (= (query-result method endpoint ["extract" [["function" "count"] "status" "certname"]
                                            ["~" "certname" ".*"]
                                            ["group_by" "status" "certname"]])
             #{{:certname "bar.local" :status "unchanged" :count 1}
               {:certname "foo.local" :status "unchanged" :count 1}})))

    (testing "projected function call with no grouping"
      (is (= (query-result method endpoint ["extract" [["function" "count"]]
                                            ["~" "certname" ".*"]])
             #{{:count 2}})))

    (testing "three projected columns"
      (is (= (query-result method endpoint ["extract" ["hash" "certname" "transaction_uuid"]
                                            ["=" "certname" (:certname basic)]])
             #{(select-keys basic [:hash :certname :transaction_uuid])})))))

(deftestseq query-report-data
  [[version field] [[:v4 :logs] [:v4 :metrics]]
   method [:get :post]]
  (let [report-hash (:hash (store-example-report! (:basic reports) (now)))
        basic (assoc (:basic reports) :hash report-hash)
        get-data (fn [hash field]
                   (query-result method (format "/v4/reports/%s/%s" hash field)))]
    (testing (format "%s endpoint returns the proper data" (name field))
      (is (= (get-data report-hash (name field)) (-> basic field set))))))

(deftestseq query-with-paging
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic1 (:basic reports)
        _      (store-example-report! basic1 (now))
        basic2 (:basic2 reports)
        _      (store-example-report! basic2 (now))]

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
                                   [basic1 basic2])
                 (set (munge-reports-for-comparison results)))))))))

(def my-reports
  (-> reports
      (assoc-in [:basic  :report_format] 3)
      (assoc-in [:basic2 :report_format] 4)
      (assoc-in [:basic3 :report_format] 6)
      (assoc-in [:basic4 :report_format] 5)
      (assoc-in [:basic  :transaction_uuid] "aaaaaaaa-1111-1111-aaaa-111111111111")
      (assoc-in [:basic2 :transaction_uuid] "bbbbbbbb-2222-2222-bbbb-222222222222")
      (assoc-in [:basic3 :transaction_uuid] "cccccccc-3333-3333-cccc-333333333333")
      (assoc-in [:basic4 :transaction_uuid] "dddddddd-4444-4444-dddd-444444444444")
      (assoc-in [:basic  :start_time] (-> 1 days ago))
      (assoc-in [:basic2 :start_time] (-> 4 days ago))
      (assoc-in [:basic3 :start_time] (-> 3 days ago))
      (assoc-in [:basic4 :start_time] (-> 2 days ago))
      (assoc-in [:basic  :puppet_version] "3.0.1")
      (assoc-in [:basic2 :puppet_version] "3.0.1")
      (assoc-in [:basic3 :puppet_version] "4.1.0")
      (assoc-in [:basic4 :puppet_version] "4.1.0")
      (assoc-in [:basic  :configuration_version] "bbb")
      (assoc-in [:basic2 :configuration_version] "aaa")
      (assoc-in [:basic3 :configuration_version] "xxx")
      (assoc-in [:basic4 :configuration_version] "yyy")))

(deftestseq paging-results
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{report1 :basic
         report2 :basic2
         report3 :basic3
         report4 :basic4} my-reports
        report-count 4]

    (store-example-report! report1 (now))
    (store-example-report! report2 (now))
    (store-example-report! report3 (now))
    (store-example-report! report4 (now))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 report-count]]]
        (let [results (query-result method endpoint
                                    ["=" "certname" "foo.local"]
                                    {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

      (testing "numerical fields"
        (doseq [[order expected] [["asc" [report1 report2 report4 report3]]
                                  ["desc" [report3 report4 report2 report1]]]]
          (testing order
            (let [actual (ordered-query-result method endpoint
                                               ["=" "certname" "foo.local"]
                                               {:order_by
                                                (vector-param
                                                  method
                                                  [{"field" "report_format"
                                                    "order" order}])}
                                               munge-actual-reports)]
              (is (= actual (munge-expected-reports expected)))))))

      (testing "alphabetical fields"
        (doseq [[order expected] [["asc"  [report1 report2 report3 report4]]
                                  ["desc" [report4 report3 report2 report1]]]]
          (testing order
            (let [actual (ordered-query-result method endpoint
                                               ["=" "certname" "foo.local"]
                                               {:order_by (vector-param
                                                            method
                                                            [{"field" "transaction_uuid"
                                                              "order" order}])}
                                               munge-actual-reports)]
              (is (= (map :transaction_uuid actual)
                     (map :transaction_uuid (munge-expected-reports expected))))))))

    (testing "timestamp fields"
      (doseq [[order expected] [["asc"  [report2 report3 report4 report1]]
                                ["desc" [report1 report4 report3 report2]]]]
        (testing order
          (let [actual (ordered-query-result method endpoint
                                             ["=" "certname" "foo.local"]
                                             {:order_by (vector-param method [{"field" "start_time"
                                                                               "order" order}])}
                                             munge-actual-reports)]
            (is (= actual
                   (munge-expected-reports expected)))))))

    (testing "multiple fields"
      (doseq [[[puppet-version-order conf-version-order] expected]
              [[["asc" "desc"] [report1 report2 report4 report3]]
               [["desc" "asc"] [report3 report4 report2 report1]]]]
        (testing (format "puppet-version %s configuration-version %s" puppet-version-order conf-version-order)
          (let [actual (ordered-query-result method endpoint
                                             ["=" "certname" "foo.local"]
                                             {:order_by
                                              (vector-param method [{"field" "puppet_version"
                                                                     "order" puppet-version-order}
                                                                    {"field" "configuration_version"
                                                                     "order" conf-version-order}])}
                                             munge-actual-reports)]
            (is (= actual
                   (munge-expected-reports expected)))))))

    (testing "offset"
      (doseq [[order offset expected] [["asc" 0 [report1 report2 report4 report3]]
                                       ["asc" 1 [report2 report4 report3]]
                                       ["asc" 2 [report4 report3]]
                                       ["asc" 3 [report3]]
                                       ["asc" 4 []]
                                       ["desc" 0 [report3 report4 report2 report1]]
                                       ["desc" 1 [report4 report2 report1]]
                                       ["desc" 2 [report2 report1]]
                                       ["desc" 3 [report1]]
                                       ["desc" 4 []]]]
        (testing order
          (let [actual (ordered-query-result method endpoint
                                             ["=" "certname" "foo.local"]
                                             {:order_by (vector-param
                                                          method [{"field" "report_format"
                                                                   "order" order}])
                                              :offset offset}
                                             munge-actual-reports)]
            (is (= actual
                   (munge-expected-reports expected)))))))))

(deftestseq invalid-queries
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [status body]} (query-response method endpoint ["<" "environment" 0])]
    (is (re-matches #".*Query operators .*<.* not allowed .* environment"
                    body))
    (is (= 400 status)))

  (let [{:keys [status body]} (query-response method endpoint ["=" "timestamp" 0])]
    (is (re-find #"'timestamp' is not a queryable object for reports"
                 body))
    (is (= 400 status))))

(deftestseq query-by-status
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))
        basic3 (assoc (:basic3 reports) :status "changed")
        _ (store-example-report! basic3 (now))
        basic4 (assoc (:basic4 reports) :status "failed")
        _ (store-example-report! basic4 (now))]

    (testing "should return all reports for a certname"
      (let [unchanged-reports (query-result method endpoint ["=" "status" "unchanged"]
                                            {} munge-reports-for-comparison)
            changed-reports (query-result method endpoint ["=" "status" "changed"]
                                          {} munge-reports-for-comparison)
            failed-reports (query-result method endpoint ["=" "status" "failed"]
                                         {} munge-reports-for-comparison)]

        (is (= 2 (count unchanged-reports)))
        (is (every? #(= "unchanged" (:status %)) unchanged-reports))

        (is (= unchanged-reports (reports-response version [basic basic2])))

        (is (= 1 (count changed-reports)))
        (is (= changed-reports (reports-response version [basic3])))

        (is (= 1 (count failed-reports)))
        (is (= failed-reports (reports-response version [basic4])))))))

(deftestseq query-by-certname-with-environment
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))]

    (testing "should return all reports for a certname"
      (let [result (query-result method "/v4/environments/DEV/reports" ["=" "certname" (:certname basic)] {} munge-reports-for-comparison)]
        (is (every? #(= "DEV" (:environment %)) result))
        (is (= result (reports-response version [basic])))))

    (testing "PROD environment"
      (is (=
           {:error "No information is known about environment PROD"}
           (json/parse-string
            (:body
             (query-response method "/v4/environments/PROD/reports"
                           ["=" "certname" (:certname basic)]))
            true))))))

(deftestseq query-by-puppet-version
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (assoc (:basic2 reports) :puppet_version "3.6.0")
        _ (store-example-report! basic2 (now))
        basic3 (assoc (:basic3 reports) :puppet_version "3.0.3")
        _ (store-example-report! basic3 (now))

        v301 (query-result method endpoint ["=" "puppet_version" "3.0.1"]
                           {} munge-reports-for-comparison)
        v360 (query-result method endpoint ["=" "puppet_version" "3.6.0"]
                           {} munge-reports-for-comparison)
        v30x (query-result method endpoint ["~" "puppet_version" "3\\.0\\..*"]
                           {} munge-reports-for-comparison)]

    (is (= 1 (count v301)))
    (is (= v301 (reports-response version [basic])))

    (is (= 1 (count v360)))
    (is (= v360 (reports-response version [basic2])))

    (is (= 2 (count v30x)))
    (is (= v30x (reports-response version [basic basic3])))))

(deftestseq query-by-report-format
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (assoc (:basic2 reports) :report_format 5)
        _ (store-example-report! basic2 (now))
        basic3 (assoc (:basic3 reports) :report_format 6)
        _ (store-example-report! basic3 (now))

        v4-format (query-result method endpoint ["=" "report_format" 4]
                                {} munge-reports-for-comparison)
        v5-format (query-result method endpoint ["and"
                                                 [">" "report_format" 4]
                                                 ["<" "report_format" 6]]
                                {} munge-reports-for-comparison)
        v6-format (query-result method endpoint ["and"
                                                 [">" "report_format" 4]
                                                 ["<=" "report_format" 6]]
                                {} munge-reports-for-comparison)]

    (is (= 1 (count v4-format)))
    (is (= v4-format (reports-response version [basic])))

    (is (= 1 (count v5-format)))
    (is (= v5-format (reports-response version [basic2])))

    (is (= 2 (count v6-format)))
    (is (= v6-format (reports-response version [basic2 basic3])))))

(deftestseq query-by-configuration-version
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))

        basic-result-body (query-result method endpoint ["=" "configuration_version" "a81jasj123"]
                                        {} munge-reports-for-comparison)
        basic2-result-body (query-result method endpoint ["~" "configuration_version" ".*23"]
                                         {} munge-reports-for-comparison)]

    (is (= 1 (count basic-result-body)))
    (is (= basic-result-body
           (reports-response version [basic])))

    (is (= 2 (count basic2-result-body)))
    (is (= basic2-result-body
           (reports-response version [basic basic2])))))

(deftestseq query-by-start-and-end-time
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))

        basic-result (query-result method endpoint ["=" "start_time" "2011-01-01T12:00:00-03:00"]
                                     {} munge-reports-for-comparison)
        basic-range (query-result method endpoint ["and"
                                                   [">" "start_time" "2010-01-01T12:00:00-03:00"]
                                                   ["<" "end_time" "2012-01-01T12:00:00-03:00"]]
                                  {} munge-reports-for-comparison)
        all-reports (query-result method endpoint ["and"
                                                   [">" "start_time" "2010-01-01T12:00:00-03:00"]
                                                   ["<" "end_time" "2014-01-01T12:00:00-03:00"]]
                                  {} munge-reports-for-comparison)
        basic2-result (query-result method endpoint ["=" "end_time" "2013-08-28T19:10:00-03:00"]
                                    {} munge-reports-for-comparison)]

    (is (= 1 (count basic-result)))
    (is (= basic-result (reports-response version [basic])))

    (is (= 1 (count basic-range)))
    (is (= basic-range (reports-response version [basic])))

    (is (= 1 (count basic2-result)))
    (is (= basic2-result (reports-response version [basic2])))

    (is (= 2 (count all-reports)))
    (is (= all-reports (reports-response version [basic basic2])))))

(defn ts->str [ts]
  (tfmt/unparse (tfmt/formatters :date-time) (tcoerce/to-date-time ts)))

(deftestseq query-by-receive-time
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        stored-basic (store-example-report! basic (now))
        basic-result (query-result method endpoint
                                   ["=" "receive_time" (ts->str (:receive_time stored-basic))]
                                   {} munge-reports-for-comparison)]

    (is (= 1 (count basic-result)))
    (is (= basic-result (reports-response version [basic])))))

(deftestseq query-by-transaction-uuid
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))

        basic-result (query-result method endpoint
                                   ["=" "transaction_uuid" "68b08e2a-eeb1-4322-b241-bfdf151d294b"]
                                   {} munge-reports-for-comparison)
        all-results (query-result method endpoint ["~" "transaction_uuid" "b$"]
                                  {} munge-reports-for-comparison)]

    (is (= 1 (count basic-result)))
    (is (= basic-result (reports-response version [basic])))

    (is (= 2 (count all-results)))
    (is (= all-results (reports-response version [basic basic2])))))

(deftestseq latest-report-queries
  [[version endpoint] endpoints
   method [:get :post]]
  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))
        latest (query-result method endpoint ["=" "latest_report?" true]
                              {} munge-reports-for-comparison)
        latest2 (query-result method endpoint
                              ["and" ["=" "latest_report?" true] ["=" "noop" false]]
                              {} munge-reports-for-comparison)
        latest3 (query-result method endpoint ["and" ["=" "latest_report?" true] ["=" "noop" true]]
                              {} munge-reports-for-comparison)]

    (is (= 1 (count latest)))
    (is (= latest (reports-response version [basic2])))

    (is (= 0 (count latest2)))
    (is (= latest2 (reports-response version [])))

    (is (= 1 (count latest3)))
    (is (= latest3 (reports-response version [basic2])))

    (let [basic4 (assoc (:basic4 reports) :certname "bar.local")
          _ (store-example-report! basic4 (now))
          latest4 (query-result method endpoint ["=" "latest_report?" true]
                                {} munge-reports-for-comparison)]
      (is (= 2 (count latest4)))
      (is (= latest4 (reports-response version [basic2 basic4]))))))

(deftestseq query-by-hash
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))
        basic-result (query-result method endpoint ["=" "hash" hash1]
                                   {} munge-reports-for-comparison)]

    (is (= 1 (count basic-result)))
    (is (= basic-result (reports-response version [basic])))))

(def invalid-projection-queries
  (omap/ordered-map
    ;; Top level extract using invalid fields should throw an error
    ["extract" "nothing" ["~" "certname" ".*"]]
    #"Can't extract unknown 'reports' field 'nothing'.*Acceptable fields are.*"

    ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
    #"Can't extract unknown 'reports' fields: 'nothing', 'nothing2'.*Acceptable fields are.*"))

(deftestseq invalid-projections
  [[version endpoint] endpoints
   method [:get :post]]

  (doseq [[query msg] invalid-projection-queries]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (query-response method endpoint query)]
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
  [[version endpoint] endpoints
   method [:get :post]]

  (doseq [[query msg] (get pg-versioned-invalid-regexps endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))

(def no-parent-endpoints [[:v4 "/v4/reports/foo/events"]
                          [:v4 "/v4/reports/foo/metrics"]
                          [:v4 "/v4/reports/foo/logs"]])

(deftestseq unknown-parent-handling
  [[version endpoint] no-parent-endpoints
   method [:get :post]]

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= status http/status-not-found))
    (is (= {:error "No information is known about report foo"} (json/parse-string body true)))))

(deftest reports-retrieval
  (let [basic (:basic my-reports)
        report-hash (:hash (store-example-report! basic (now)))]
    (testing "report-exists? function"
      (is (= true (qe/object-exists? :report report-hash)))
      (is (= false (qe/object-exists? :report "chrissyamphlett"))))))
