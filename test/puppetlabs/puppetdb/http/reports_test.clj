(ns puppetlabs.puppetdb.http.reports-test
  (:require [puppetlabs.puppetdb.query-eng :as qe]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [clojure.test :refer :all]
            [flatland.ordered.map :as omap]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.testutils :refer [paged-results dotestseq]]
            [puppetlabs.puppetdb.testutils.db :refer [with-test-db]]
            [puppetlabs.puppetdb.testutils.http
             :refer [*app*
                     are-error-response-headers
                     deftest-http-app
                     query-response
                     query-result
                     ordered-query-result
                     with-http-app
                     with-http-app*
                     vector-param]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!
                                                           munge-reports-for-comparison]]
            [puppetlabs.puppetdb.time :as tfmt
             :refer [ago days now to-date-time]])
  (:import
   (java.net HttpURLConnection)))

(def endpoints [[:v4 "/v4/reports"]])

(deftest-http-app query-by-parameters
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))
        basic-with-hash (assoc basic :hash report-hash)]

    (doseq [field ["certname" "hash" "puppet_version" "report_format"
                   "configuration_version" "start_time" "end_time"
                   "transaction_uuid" "status" "producer" "noop_pending"]
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
          (is (= result
                 (munge-reports-for-comparison [basic]))))))))

(deftest-http-app query-with-projection
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))
        bar-report-hash (:hash (store-example-report! (assoc basic :certname "bar.local") (now)))
        basic (assoc basic :hash report-hash)]

    (testing "one projected column"
      (is (= (query-result method endpoint ["extract" "hash"
                                                 ["=" "certname" (:certname basic)]])
             #{{:hash report-hash}})))

    (testing "one projected column with no subquery"
      (is (= (query-result method endpoint ["extract" "certname"])
             #{{:certname "foo.local"} {:certname "bar.local"}})))

    (testing "one projected column with no subquery and an aggregate function"
      (is (= (query-result method endpoint ["extract" [["function" "count"] "certname"]
                                            ["group_by" "certname"]])
             #{{:count 1 :certname "foo.local"}
               {:count 1 :certname "bar.local"}})))

    (testing "logs projected"
      (is (= (query-result method endpoint ["extract" "logs"
                                            ["=" "certname" (:certname basic)]])
             #{{:logs {:href (format "/pdb/query/v4/reports/%s/logs" report-hash)
                       :data (get-in basic [:logs :data])}}})))

    (testing "metrics projected"
      (is (= (query-result method endpoint ["extract" "metrics"
                                            ["=" "certname" (:certname basic)]])
             #{{:metrics {:href (format "/pdb/query/v4/reports/%s/metrics" report-hash)
                          :data (get-in basic [:metrics :data])}}})))

    (testing "one projected column with a not"
      (is (= (query-result method endpoint ["extract" "hash"
                                            ["not" ["=" "certname" (:certname basic)]]])
             #{{:hash bar-report-hash}})))

    (testing "projected aggregate count call"
      (is (= (query-result method endpoint ["extract" [["function" "count"] "status"]
                                            ["~" "certname" ".*"]
                                            ["group_by" "status"]])
             #{{:status "unchanged", :count 2}})))

    (testing "projected aggregate count call"
      (is (= (query-result method endpoint ["extract" [["function" "count" "certname"]
                                                       ["function" "min" "producer_timestamp"]]])
             #{{:count 2
                :min "2011-01-01T15:11:00.000Z"}})))

    (testing "group by function result"
      (is (= (query-result method endpoint
                           ["extract" [["function" "to_string" "producer_timestamp" "FMDAY"]
                                       ["function" "count"]]
                            ["group_by" ["function" "to_string" "producer_timestamp" "FMDAY"]]])

             #{{:to_string "SATURDAY" :count 2}})))

    (testing "group by function result and a column"
      (is (= (query-result method endpoint
                           ["extract" ["certname"
                                       ["function" "to_string" "producer_timestamp" "FMDAY"]
                                       ["function" "count"]]
                            ["group_by" "certname"
                             ["function" "to_string" "producer_timestamp" "FMDAY"]]])

             #{{:count 1 :certname "foo.local" :to_string "SATURDAY"}
               {:count 1 :certname "bar.local" :to_string "SATURDAY"}})))

    (testing "projected aggregate sum call"
      (is (= (query-result method endpoint ["extract" ["status"]
                                            ["group_by" "status"]])
             #{{:status "unchanged"}}))
      (is (= (query-result method endpoint ["extract"
                                            [["function" "sum" "report_format"] "status"]
                                            ["group_by" "status"]])
             #{{:status "unchanged", :sum 8}}))
      (is (= (query-result method endpoint ["extract"
                                            [["function" "sum" "report_format"] "status"]
                                            ["~" "certname" ".*"]
                                            ["group_by" "status"]])
             #{{:status "unchanged", :sum 8}})))

    (testing "using the `in`-`array` operator"
      (is (= (query-result method endpoint ["extract" [["function" "count"] "status" "certname"]
                                            ["in" "certname" ["array" ["bar.local" "foo.local"]]]
                                            ["group_by" "status" "certname"]])
             #{{:certname "bar.local" :status "unchanged" :count 1}
               {:certname "foo.local" :status "unchanged" :count 1}}))
      (is (= #{{:hash "5bc5d561c7912570a7c7f525b815477cdaed70a2"}
               {:hash "5067c5ac56f39501e504c0b76186d31ec1b5ca94"}}
             (query-result method endpoint ["extract" ["hash"]
                                            ["in" "hash"
                                             ["array" ["5bc5d561c7912570a7c7f525b815477cdaed70a2"
                                                       "5067c5ac56f39501e504c0b76186d31ec1b5ca94"]]]])))
      (is (= (query-result method endpoint ["extract" [["function" "count"] "status" "certname"]
                                            ["or"
                                             ["in" "status" ["array" ["unchanged"]]]
                                             ["in" "certname" ["array" ["baz.local"]]]]
                                            ["group_by" "status" "certname"]])
             #{{:certname "bar.local" :status "unchanged" :count 1}
               {:certname "foo.local" :status "unchanged" :count 1}})))

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

(deftest-http-app query-with-type-any
  [[_version endpoint] endpoints
    method [:get :post]]

  (doseq [queries [["extract" [] ["=" "type" "any"]]
                   ["extract" [] ["and"
                                  ["=" "type" "any"]
                                  ["=" "node_state" "any"]]]
                   ["extract" [] ["or"
                                  ["=" "type" "any"]
                                  ["=" "node_state" "any"]]]
                   ["extract" [] ["not"
                                  ["=" "type" "any"]]]]]
    (let [basic (:basic reports)
          _ (store-example-report! basic (now))
          plan-report (-> (:basic2 reports)
                        (assoc :type "plan"))
          _ (store-example-report! plan-report (now))
          query-result (query-result method endpoint queries)]

      (testing "should query all reports"
        (is (= (count query-result) 2)))

      (testing "results should contain an agent and a plan report"
        (is (= (sort (vector (:type (first query-result)) (:type (last query-result))))
               ["agent" "plan"]))))))

(deftest-http-app month-from-producer-timestamp
  [[_version endpoint] endpoints
    method [:get :post]]

    (store-example-report! (:basic reports) (now))
    (testing "to_string month format"
      (is (= (query-result method endpoint
                         ["extract" [["function" "to_string" "producer_timestamp" "month"]]])
  ; The response is blank-padded to 9 characters. More info in the PostgreSQL documentation about
  ; formatting masks: https://www.postgresql.org/docs/11/functions-formatting.html#FUNCTIONS-FORMATTING-DATETIME-TABLE
           #{{:to_string "january  "}}))))

(deftest-http-app query-report-with-malformed-json
  [_version [:v4]]
  (store-example-report! (:basic reports) (now))
  (let [{:keys [status body]} (query-response :get "/v4/reports" "[\"=\"")]
    (testing "malformed json queries don't return status 500 responses"
      (is (= 400 status))
      (is (= (str "Json parse error at line 1, column 5:\n\n"
                  "[\"=\"\n"
                  "   ^\n\n"
                  "Unexpected end-of-input: expected close marker for Array "
                  "(start marker at [Source: (StringReader); line: 1, column: 1])") body)))))

(deftest-http-app query-report-data
  [[_version field] [[:v4 :logs] [:v4 :metrics]]
   method [:get :post]]
  (let [report-hash (:hash (store-example-report! (:basic reports) (now)))
        basic (assoc (:basic reports) :hash report-hash)
        get-data (fn [hash field]
                   (query-result method (format "/v4/reports/%s/%s" hash field)))]
    (testing (format "%s endpoint returns the proper data" (name field))
      (is (= (get-data report-hash (name field))
             (-> basic field :data set))))))

(deftest-http-app query-report-data-with-pretty-printing
  [[_version field] [[:v4 :logs] [:v4 :metrics]]
   method [:get :post]]
  (let [report-hash (:hash (store-example-report! (:basic reports) (now)))
        basic (assoc (:basic reports)
                     :hash report-hash)
        get-data (fn [hash field]
                   (query-result method
                                 (format "/v4/reports/%s/%s" hash field)
                                 nil
                                 {:pretty true}))]
    (testing (format "%s endpoint returns the proper data" (name field))
      (is (= (get-data report-hash (name field))
             (-> basic field :data set))))))

(deftest-http-app query-with-pretty-printing
  [[_version endpoint] endpoints
   method [:get :post]]
  (let [basic1 (:basic reports)]
    (store-example-report! basic1 (now))

    (testing "should support pretty printing in reports"
      (let [results (query-result method endpoint nil {:pretty true})]
        (is (seq results))))))

(deftest-http-app query-with-paging
  [[_version endpoint] endpoints
   method [:get :post]]
  (let [basic1 (:basic reports)
        basic2 (:basic2 reports)]
    (store-example-report! basic1 (now))
    (store-example-report! basic2 (now))
    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through reports " label " counts")
        (let [results (paged-results
                       method
                       {:app-fn *app*
                        :path endpoint
                        :query ["=" "certname" (:certname basic1)]
                        :limit 1
                        :total 2
                        :params {:order_by (vector-param method
                                                         [{:field "transaction_uuid"
                                                           :order "desc"}])}
                        :include_total count?})]
          (is (= 2 (count results)))
          (is (= (munge-reports-for-comparison [basic1 basic2])
                 (munge-reports-for-comparison results))))))))

(deftest-http-app reports-json-vs-jsonb
  [[_version endpoint] endpoints
  method [:get :post]]

  (let [munge (fn [d] (set (map #(update-in % [:resource_events :data] set) d)))
        basic1 (:basic reports)
        _ (store-example-report! basic1 (now))
        basic2 (assoc (:basic2 reports) :certname "bar.local")
        _ (store-example-report! basic2 (now))
        initial-response (munge (query-result method endpoint))]

    (testing "response is the same with logs split between json and jsonb"
      (jdbc/do-commands
       "update reports
          set logs_json=(select logs::json from reports
                           where certname='foo.local')
            where reports.certname='foo.local'"
       "update reports set logs=null where certname='foo.local'")

      (is (= (munge (query-result method endpoint)) initial-response)))

    (testing "response is the same with all logs in json column"
      (jdbc/do-commands
       "update reports
          set logs_json=(select logs::json from reports
                           where certname='bar.local')
            where reports.certname='bar.local'"
        "update reports set logs=null where certname='bar.local'")

      (is (= (munge (query-result method endpoint)) initial-response)))))

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

(deftest-http-app paging-results
  [[_version endpoint] endpoints
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
          (is (= expected actual)))))

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
                                                    "order" order}])})]
              (is (= (munge-reports-for-comparison expected)
                     (munge-reports-for-comparison actual)))))))

      (testing "alphabetical fields"
        (doseq [[order expected] [["asc"  [report1 report2 report3 report4]]
                                  ["desc" [report4 report3 report2 report1]]]]
          (testing order
            (let [actual (ordered-query-result method endpoint
                                               ["=" "certname" "foo.local"]
                                               {:order_by (vector-param
                                                            method
                                                            [{"field" "transaction_uuid"
                                                              "order" order}])})]
              (is (= (map :transaction_uuid (munge-reports-for-comparison expected))
                     (map :transaction_uuid (munge-reports-for-comparison actual))))))))

    (testing "timestamp fields"
      (doseq [[order expected] [["asc"  [report2 report3 report4 report1]]
                                ["desc" [report1 report4 report3 report2]]]]
        (testing order
          (let [actual (ordered-query-result method endpoint
                                             ["=" "certname" "foo.local"]
                                             {:order_by (vector-param method [{"field" "start_time"
                                                                               "order" order}])})]
            (is (= (munge-reports-for-comparison expected)
                   (munge-reports-for-comparison actual)))))))

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
                                                                     "order" conf-version-order}])})]
            (is (= (munge-reports-for-comparison expected)
                   (munge-reports-for-comparison actual)))))))

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
                                              :offset offset})]
            (is (= (munge-reports-for-comparison expected)
                   (munge-reports-for-comparison actual)))))))))

(deftest-http-app invalid-queries
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [status body]} (query-response method endpoint ["<" "environment" 0])]
    (is (re-matches #".*Query operators .*<.* not allowed .* environment"
                    body))
    (is (= 400 status)))

  (let [{:keys [status body]} (query-response method endpoint ["=" "timestamp" 0])]
    (is (re-find #"'timestamp' is not a queryable object for reports"
                 body))
    (is (= 400 status))))

(deftest-http-app invalid-extract
  [[_version endpoint] endpoints
   method [:get :post]]
   (let [bad-queries [["extract"
                       [["function" "count"] "certname"]
                       ["null?" "type" false]
                       ["groupy_by" "certname"]]
                      ["extract"
                       [["function" "count"] "certname"]
                       ["null?" "type" false]
                       ["some_invalid_string"]]]
         good-queries [["extract" ["hash" "certname" "transaction_uuid"]
                        ["=" "certname" "foo.com"]]
                       ["extract" "transaction_uuid"
                        ["=" "certname" "foo.com"]]
                       ["extract" [["function" "count"] "status"]
                        ["=" "certname" "foo.com"]
                        ["group_by" "status"]]]]
  (doseq [bad-query bad-queries]
    (testing (str "Ensure bad query fails: " (pr-str bad-query))
      (let [{:keys [body status]} (query-response method endpoint bad-query)]
        (is (re-matches #".* is not a valid expression for \"extract\".*" body))
        (is (= 400 status)))))

  (doseq [good-query good-queries]
    (testing (str "Ensure good query succeeds: " (pr-str good-query))
      (let [{:keys [_ status]} (query-response method endpoint good-query)]
        (is (= 200 status)))))))

(deftest-http-app query-by-status
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))
        basic3 (assoc (:basic3 reports) :status "changed")
        _ (store-example-report! basic3 (now))
        basic4 (assoc (:basic4 reports) :status "failed")
        _ (store-example-report! basic4 (now))]

    (testing "should return all reports based on their status"
      (let [unchanged-reports (query-result method endpoint ["=" "status" "unchanged"]
                                            {} munge-reports-for-comparison)
            changed-reports (query-result method endpoint ["=" "status" "changed"]
                                          {} munge-reports-for-comparison)
            failed-reports (query-result method endpoint ["=" "status" "failed"]
                                         {} munge-reports-for-comparison)]

        (is (= 2 (count unchanged-reports)))
        (is (every? #(= "unchanged" (:status %)) unchanged-reports))

        (is (= (munge-reports-for-comparison [basic basic2])
               unchanged-reports))

        (is (= 1 (count changed-reports)))
        (is (= (munge-reports-for-comparison [basic3])
               changed-reports))

        (is (= 1 (count failed-reports)))
        (is (= (munge-reports-for-comparison [basic4])
               failed-reports))))))

(deftest-http-app query-by-certname-with-environment
  [method [:get :post]]

  (let [basic (:basic reports)]
    (store-example-report! basic (now))
    (testing "should return all reports for a certname"
      (let [result (query-result method
                                 "/v4/environments/DEV/reports"
                                 ["=" "certname" (:certname basic)]
                                 {}
                                 munge-reports-for-comparison)]
        (is (every? #(= "DEV" (:environment %)) result))
        (is (= (munge-reports-for-comparison [basic])
               result))))

    (testing "PROD environment"
      (is (=
           {:error "No information is known about environment PROD"}
           (json/parse-string
            (:body
             (query-response method "/v4/environments/PROD/reports"
                           ["=" "certname" (:certname basic)]))
            true))))))

(deftest-http-app query-by-puppet-version
  [[_version endpoint] endpoints
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
    (is (= v301 (munge-reports-for-comparison [basic])))

    (is (= 1 (count v360)))
    (is (= v360 (munge-reports-for-comparison [basic2])))

    (is (= 2 (count v30x)))
    (is (= v30x (munge-reports-for-comparison [basic basic3])))))

(deftest-http-app query-by-report-format
  [[_version endpoint] endpoints
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
    (is (= v4-format (munge-reports-for-comparison [basic])))

    (is (= 1 (count v5-format)))
    (is (= v5-format (munge-reports-for-comparison [basic2])))

    (is (= 2 (count v6-format)))
    (is (= v6-format (munge-reports-for-comparison [basic2 basic3])))))

(deftest-http-app query-by-configuration-version
  [[_version endpoint] endpoints
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
           (munge-reports-for-comparison [basic])))

    (is (= 2 (count basic2-result-body)))
    (is (= basic2-result-body
           (munge-reports-for-comparison [basic basic2])))))

(deftest-http-app query-for-corrective_change
  [[_version endpoint] endpoints
   method [:get :post]]
  (let [basic (:basic reports)
        _ (store-example-report! basic (now))
        basic-result (first (query-result method endpoint))
        corrective_change-result (query-result method endpoint ["=" "corrective_change" true])]

    (testing "query on corrective_change does not fail"
      (is (empty? corrective_change-result)))
    (testing "result contains corrective_change key valued with nil"
      (is (nil? (get basic-result :corrective_change "oops"))))))

(deftest-http-app query-by-start-and-end-time
  [[_version endpoint] endpoints
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
    (is (= basic-result (munge-reports-for-comparison [basic])))

    (is (= 1 (count basic-range)))
    (is (= basic-range (munge-reports-for-comparison [basic])))

    (is (= 1 (count basic2-result)))
    (is (= basic2-result (munge-reports-for-comparison [basic2])))

    (is (= 2 (count all-reports)))
    (is (= all-reports (munge-reports-for-comparison [basic basic2])))))

(defn ts->str [ts]
  (tfmt/unparse (tfmt/formatters :date-time) (to-date-time ts)))

(deftest-http-app query-by-receive-time
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        stored-basic (store-example-report! basic (now))
        basic-result (query-result method endpoint
                                   ["=" "receive_time" (ts->str (:receive_time stored-basic))]
                                   {} munge-reports-for-comparison)]

    (is (= 1 (count basic-result)))
    (is (= basic-result (munge-reports-for-comparison [basic])))))

(deftest-http-app query-by-transaction-uuid
  [[_version endpoint] endpoints
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
    (is (= basic-result (munge-reports-for-comparison [basic])))

    (is (= 2 (count all-results)))
    (is (= all-results (munge-reports-for-comparison [basic basic2])))))

(deftest-http-app latest-report-queries
  [[_version endpoint] endpoints
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
    (is (= latest (munge-reports-for-comparison [basic2])))

    (is (= 0 (count latest2)))
    (is (= latest2 (munge-reports-for-comparison [])))

    (is (= 1 (count latest3)))
    (is (= latest3 (munge-reports-for-comparison [basic2])))

    (let [basic4 (assoc (:basic4 reports) :certname "bar.local")
          _ (store-example-report! basic4 (now))
          latest4 (query-result method endpoint ["=" "latest_report?" true]
                                {} munge-reports-for-comparison)]
      (is (= 2 (count latest4)))
      (is (= latest4 (munge-reports-for-comparison [basic2 basic4]))))))

(deftest-http-app query-by-hash
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        _ (store-example-report! basic2 (now))
        basic-result (query-result method endpoint ["=" "hash" hash1]
                                   {} munge-reports-for-comparison)]

    (is (= 1 (count basic-result)))
    (is (= basic-result (munge-reports-for-comparison [basic])))))

(deftest-http-app report-subqueries
  [[_version endpoint] endpoints
   method [:get :post]]

  (store-example-report! (:basic reports) (now))
  (store-example-report! (:basic2 reports) (now))
  (store-example-report! (:basic3 reports) (now))

  (are [query expected]
      (is (= expected
             (query-result method endpoint query)))

    ;;;;;;;;;;;;;;;
    ;; Event subqueries
    ;;;;;;;;;;;;;;;

    ;; In: select_events
    ["extract" "certname"
     ["in" "hash"
      ["extract" "report"
       ["select_events"
        ["=" "file" "bar"]]]]]
    #{{:certname "foo.local"}}

    ;; In: from events
    ["extract" "certname"
     ["in" "hash"
      ["from" "events"
       ["extract" "report"
        ["=" "file" "bar"]]]]]
    #{{:certname "foo.local"}}

    ;; Implicit subqueries
    ["extract" "certname"
     ["subquery" "events"
      ["=" "file" "bar"]]]
    #{{:certname "foo.local"}}))

(def invalid-projection-queries
  (omap/ordered-map
    ;; Top level extract using invalid fields should throw an error
    ["extract" "nothing" ["~" "certname" ".*"]]
    #"Can't extract unknown 'reports' field 'nothing'.*Acceptable fields are.*"

    ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
    #"Can't extract unknown 'reports' fields 'nothing' and 'nothing2'.*Acceptable fields are.*"))

(deftest-http-app invalid-projections
  [[_version endpoint] endpoints
   method [:get :post]]

  (doseq [[query msg] invalid-projection-queries]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body headers]} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
        (are-error-response-headers headers)))))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
    "/v4/reports" (omap/ordered-map
                  ["~" "certname" "*abc"]
                  #".*invalid regular expression: quantifier operand invalid"

                  ["~" "certname" "[]"]
                  #".*invalid regular expression: brackets.*not balanced")))

(deftest-http-app pg-invalid-regexps
  [[_version endpoint] endpoints
   method [:get :post]]

  (doseq [[query msg] (get pg-versioned-invalid-regexps endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body headers]} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
        (are-error-response-headers headers)))))

(def no-parent-endpoints [[:v4 "/v4/reports/foo/events"]
                          [:v4 "/v4/reports/foo/metrics"]
                          [:v4 "/v4/reports/foo/logs"]])

(deftest-http-app unknown-parent-handling
  [[_version endpoint] no-parent-endpoints
   method [:get :post]]
  (let [{:keys [status body headers]} (query-response method endpoint)]
    (is (= HttpURLConnection/HTTP_NOT_FOUND status))
    (is (= ["Content-Type"] (keys headers)))
    (is (http/json-utf8-ctype? (headers "Content-Type")))
    (is (= {:error "No information is known about report foo"} (json/parse-string body true)))))

(deftest report-filter-config
  (let [query ["extract" "certname" ["subquery" "reports" ["order_by" "certname"]]]]
    (testing "agent report filter added"
       (with-test-db
         (with-http-app
           (store-example-report! (:basic my-reports) (now))
           (dotestseq [[_version endpoint] endpoints
                      method [:get :post]]
             ;; This is abusing the existence of PDB-4734 to throw an error from a malformed AST query
             (let [{:keys [status body]} (query-response method endpoint query)]
               (is (= status HttpURLConnection/HTTP_BAD_REQUEST))
               (is (re-matches #"(?s)AST validation failed, but was successfully converted to SQL.*Unrecognized AST clause.*" body)))))))

    (testing "agent report filter can be disabled"
       (with-test-db
         (with-http-app* (fn [globals] (assoc globals :add-agent-report-filter false))
           (store-example-report! (:basic my-reports) (now))
           (dotestseq
             [[_version endpoint] endpoints
              method [:get :post]]
             (is (= #{{:certname "foo.local"}} (query-result method endpoint query)))))))))

(deftest reports-retrieval
  (with-test-db
    (let [basic (:basic my-reports)
          report-hash (:hash (store-example-report! basic (now)))]
      (testing "report-exists? function"
        (is (= true (qe/object-exists? :report report-hash)))
        (is (= false (qe/object-exists? :report "chrissyamphlett")))))))
