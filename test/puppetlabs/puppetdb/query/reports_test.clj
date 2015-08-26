(ns puppetlabs.puppetdb.query.reports-test
  (:require [clj-time.coerce :refer [to-string]]
            [clj-time.core :refer [now ago days]]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.reports :as r]
            [puppetlabs.puppetdb.query-eng :as qe]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils.reports :refer :all]))

(use-fixtures :each with-test-db)

;; TRANSFORMATION

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

(defn munge-expected-report
  [report]
  (-> report
      (update-in [:resource_events] (comp keywordize-keys munge-resource-events))
      normalize-time
      strip-expanded))

(defn munge-expected-reports
  [reports]
  (set (map munge-expected-report
            reports)))

(defn munge-actual-report
  [report]
  (-> report
      (update-in [:resource_events] (comp keywordize-keys munge-resource-events))
      normalize-time
      strip-expanded))

(defn munge-actual-reports
  "Convert actual reports to a format ready for comparison"
  [reports]
  (set (map (comp munge-actual-report
                  reports/report-query->wire-v5
                  #(update-in % [:logs :data] keywordize-keys)
                  #(update-in % [:metrics :data] keywordize-keys))
            reports)))

;; TESTS

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

(deftest reports-retrieval
  (let [basic       (:basic reports)
        report-hash (:hash (store-example-report! basic (now)))]
    (testing "should return reports based on certname"
      (let [actual (reports-query-result :v4 ["=" "certname" (:certname basic)])]
        (is (= (munge-expected-reports [basic])
               (munge-actual-reports actual)))))

    (testing "should return reports based on hash"
      (let [actual (reports-query-result :v4 ["=" "hash" report-hash])]
        (is (= (munge-expected-reports [basic])
               (munge-actual-reports actual)))))

    (testing "report-exists? function"
      (is (= true (qe/object-exists? :report report-hash)))
      (is (= false (qe/object-exists? :report "chrissyamphlett"))))))

(deftest paging-results
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
        (let [results (reports-query-result :v4 ["=" "certname" "foo.local"] {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order_by"
      (testing "rejects invalid fields"
        (is (thrown-with-msg?
             IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order_by"
             (reports-query-result :v4 ["=" "certname" "foo.local"] {:order_by [[:invalid-field :ascending]]}))))

      (testing "numerical fields"
        (doseq [[order expected] [[:ascending  [report1 report2 report4 report3]]
                                  [:descending [report3 report4 report2 report1]]]]
          (testing order
            (let [actual (reports-query-result :v4
                                               ["=" "certname" "foo.local"]
                                               {:order_by [[:report_format order]]})]
              (is (= (munge-actual-reports actual)
                     (munge-expected-reports expected)))))))

      (testing "alphabetical fields"
        (doseq [[order expected] [[:ascending  [report1 report2 report4 report3]]
                                  [:descending [report3 report4 report2 report1]]]]
          (testing order
            (let [actual (reports-query-result :v4
                                               ["=" "certname" "foo.local"]
                                               {:order_by [[:transaction_uuid order]]})]
              (is (= (munge-actual-reports actual)
                     (munge-expected-reports expected)))))))

      (testing "timestamp fields"
        (doseq [[order expected] [[:ascending  [report2 report3 report4 report1]]
                                  [:descending [report1 report4 report3 report2]]]]
          (testing order
            (let [actual (reports-query-result :v4
                                               ["=" "certname" "foo.local"]
                                               {:order_by [[:start_time order]]})]
              (is (= (munge-actual-reports actual)
                     (munge-expected-reports expected)))))))

      (testing "multiple fields"
        (doseq [[[puppet-version-order conf-version-order] expected]
                [[[:ascending :descending] [report1 report2 report4 report3]]
                 [[:descending :ascending] [report3 report4 report2 report1]]]]
          (testing (format "puppet-version %s configuration-version %s" puppet-version-order conf-version-order)
            (let [actual (reports-query-result :v4
                                               ["=" "certname" "foo.local"]
                                               {:order_by [[:puppet_version puppet-version-order]
                                                           [:configuration_version conf-version-order]]})]
              (is (= (munge-actual-reports actual)
                     (munge-expected-reports expected))))))))

    (testing "offset"
      (doseq [[order expected-sequences] [[:ascending  [[0 [report1 report2 report4 report3]]
                                                        [1 [report2 report4 report3]]
                                                        [2 [report4 report3]]
                                                        [3 [report3]]
                                                        [4 []]]]
                                          [:descending [[0 [report3 report4 report2 report1]]
                                                        [1 [report4 report2 report1]]
                                                        [2 [report2 report1]]
                                                        [3 [report1]]
                                                        [4 []]]]]]
        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (reports-query-result :v4
                                               ["=" "certname" "foo.local"]
                                               {:order_by [[:report_format order]] :offset offset})]
              (is (= (munge-actual-reports actual)
                     (munge-expected-reports expected))))))))))
