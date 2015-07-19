(ns puppetlabs.puppetdb.http.aggregate-event-counts-test
  (:require [puppetlabs.puppetdb.http :as http]
            [cheshire.core :as json]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [clj-time.core :refer [now]]
            [clj-time.coerce :as coerce]
            [puppetlabs.puppetdb.testutils :refer [assert-success! deftestseq]]
            [puppetlabs.puppetdb.testutils.event-counts :refer [get-response]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!]]))

(def endpoints [[:v4 "/v4/aggregate-event-counts"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(deftestseq ^{:hsqldb false} query-aggregate-event-counts
  [[version endpoint] endpoints]

  (store-example-report! (:basic reports) (now))

  (testing "summarize_by rejects unsupported values"
    (let [response  (get-response endpoint
                                  ["=" "certname" "foo.local"]
                                  "illegal-summarize-by"
                                  {} true)
          body      (get response :body "null")]
      (is (= (:status response) http/status-bad-request))
      (is (re-find #"Unsupported value for 'summarize_by': 'illegal-summarize-by'" body))))

  (testing "count_by rejects unsupported values"
    (let [response  (get-response endpoint
                                  ["=" "certname" "foo.local"]
                                  "certname"
                                  {"count_by" "illegal-count-by"} true)
          body      (get response :body "null")]
      (is (= (:status response) http/status-bad-request))
      (is (re-find #"Unsupported value for 'count_by': 'illegal-count-by'" body))))

  (testing "summarize_by accepts multiple parameters"
    (let [expected [{:successes 1
                     :failures 0
                     :noops 0
                     :skips 1
                     :total 1
                     :summarize_by "certname"}
                    {:successes 2
                     :skips 1
                     :failures 0
                     :noops 0
                     :total 3
                     :summarize_by "resource"}]
          response (get-response endpoint
                                 ["=" "certname" "foo.local"]
                                 "certname,resource"
                                 {} true)
          actual (json/parse-string (:body response) true)]
      (is (= (sort-by :summarize_by actual) (sort-by :summarize_by expected)))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected  {:successes 0
                     :failures 0
                     :noops 0
                     :skips 1
                     :total 1
                     :summarize_by "containing_class"}
          response  (get-response endpoint
                                  ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                  "containing_class"
                                  {"count_by"      "certname"
                                   "counts_filter" ["<" "successes" 1]})
          actual    (first (json/parse-string (:body response) true))]
      (is (= actual expected)))))

(deftestseq ^{:hsqldb false} query-distinct-event-counts
  [[version endpoint] endpoints]
  (let [current-time (now)
        current-time-str (coerce/to-string current-time)
        expected  [{:successes 1
                   :skips 1
                   :failures 1
                   :noops 0
                   :total 3
                   :summarize_by "resource"}]]
    (store-example-report! (:basic reports) current-time)
    (store-example-report! (:basic3 reports) current-time)
    (are [query] (= expected
                    (-> endpoint
                        (get-response query "resource" {"distinct_resources" true
                                                        "distinct_start_time" 0
                                                        "distinct_end_time" (now)})
                        :body
                        (json/parse-string true)))
         nil
         ["=" "certname" "foo.local"]
         ["<=" "report_receive_time" current-time-str]
         ["<=" "run_start_time" current-time-str]
         ["<=" "run_end_time" current-time-str])))

(deftestseq ^{:hsqldb false} query-with-environment
  [[version endpoint] endpoints]

  (store-example-report! (:basic reports) (now))
  (store-example-report! (assoc (:basic2 reports)
                           :certname "bar.local"
                           :environment "PROD") (now))
  (are [result query] (= result (-> (get-response endpoint
                                                  query
                                                  "resource")
                                    :body
                                    (json/parse-string true)))
       [{:successes 2
        :skips 1
        :failures 0
        :noops 0
        :total 3
        :summarize_by "resource"}]
       ["=" "environment" "DEV"]

       [{:successes 5
        :skips 1
        :failures 0
        :noops 0
        :total 6
        :summarize_by "resource"}]
       nil

       [{:successes 2
        :skips 1
        :failures 0
        :noops 0
        :total 3
        :summarize_by "resource"}]
       ["~" "environment" "DE"]

       [{:successes 3
        :skips 0
        :failures 0
        :noops 0
        :total 3
        :summarize_by "resource"}]
       ["=" "environment" "PROD"]

       [{:successes 3
        :skips 0
        :failures 0
        :noops 0
        :total 3
        :summarize_by "resource"}]
       ["~" "environment" "PR"]

       [{:successes 5
        :skips 1
        :failures 0
        :noops 0
        :total 6
        :summarize_by "resource"}]
       ["~" "environment" "D"]

       [{:successes 5
        :skips 1
        :failures 0
        :noops 0
        :total 6
        :summarize_by "resource"}]
       ["OR"
        ["=" "environment" "DEV"]
        ["=" "environment" "PROD"]]))
