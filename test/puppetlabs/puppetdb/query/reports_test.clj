(ns puppetlabs.puppetdb.query.reports-test
  (:require [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.query.reports :as r]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.testutils.reports :refer :all]
            [clj-time.core :refer [now ago days]]))

(use-fixtures :each with-test-db)

(defn order-events
  [report]
  (sort-by :hash (map #(update-in % [:resource_events] munge-resource-events) report)))

(def my-reports
  (-> reports
      (assoc-in [:basic  :report_format] 3)
      (assoc-in [:basic2 :report_format] 4)
      (assoc-in [:basic3 :report_format] 6)
      (assoc-in [:basic4 :report_format] 5)
      (assoc-in [:basic  :transaction_uuid] "aaa-111")
      (assoc-in [:basic2 :transaction_uuid] "bbb-222")
      (assoc-in [:basic3 :transaction_uuid] "ccc-444")
      (assoc-in [:basic4 :transaction_uuid] "ccc-333")
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

;; Begin tests

(deftest test-compile-report-term
  (testing "should successfully compile a valid equality query"
    (is (= ((query/compile-reports-equality :v4) "certname" "foo.local")
           {:where   "reports.certname = ?"
            :params  ["foo.local"]})))
  (testing "should fail with an invalid equality query"
    (is (thrown-with-msg?
         IllegalArgumentException #"is not a valid query term"
         ((query/compile-reports-equality :v4) "foo" "foo")))))

(deftest reports-retrieval
  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))]
    (testing "should return reports based on certname"
      (let [expected  (expected-reports [(assoc basic :hash report-hash)])
            actual    (reports-query-result :v4 ["=" "certname" (:certname basic)])]
        (is (= (order-events expected) (order-events actual)))))

    (testing "should return reports based on hash"
      (let [expected  (expected-reports [(assoc basic :hash report-hash)])
            actual    (reports-query-result :v4 ["=" "hash" report-hash])]
        (is (= (order-events expected) (order-events actual)))))))

(deftest paging-results
  (let [hash1        (:hash (store-example-report! (:basic  my-reports) (now)))
        hash2        (:hash (store-example-report! (:basic2 my-reports) (now)))
        hash3        (:hash (store-example-report! (:basic3 my-reports) (now)))
        hash4        (:hash (store-example-report! (:basic4 my-reports) (now)))
        report1      (assoc (:basic  my-reports) :hash hash1)
        report2      (assoc (:basic2 my-reports) :hash hash2)
        report3      (assoc (:basic3 my-reports) :hash hash3)
        report4      (assoc (:basic4 my-reports) :hash hash4)
        report-count 4]

    (testing "include total results count"
      (let [actual (:count (raw-reports-query-result :v4 ["=" "certname" "foo.local"] {:count? true}))]
        (is (= actual report-count))))

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
        (doseq [[order expecteds] [[:ascending  [report1 report2 report4 report3]]
                                   [:descending [report3 report4 report2 report1]]]]
          (testing order
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order_by [[:report_format order]]})]
              (is (= (order-events actual) (order-events expected)))))))

      (testing "alphabetical fields"
        (doseq [[order expecteds] [[:ascending  [report1 report2 report4 report3]]
                                   [:descending [report3 report4 report2 report1]]]]
          (testing order
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order_by [[:transaction_uuid order]]})]
              (is (= (order-events actual) (order-events expected)))))))

      (testing "timestamp fields"
        (doseq [[order expecteds] [[:ascending  [report2 report3 report4 report1]]
                                   [:descending [report1 report4 report3 report2]]]]
          (testing order
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order_by [[:start_time order]]})]
              (is (= (order-events actual) (order-events expected)))))))

      (testing "multiple fields"
        (doseq [[[puppet-version-order conf-version-order] expecteds] [[[:ascending :descending] [report1 report2 report4 report3]]
                                                                       [[:descending :ascending] [report3 report4 report2 report1]]]]
          (testing (format "puppet-version %s configuration-version %s" puppet-version-order conf-version-order)
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order_by [[:puppet_version puppet-version-order]
                                                             [:configuration_version conf-version-order]]})]
              (is (= (order-events actual) (order-events expected))))))))

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
          (doseq [[offset expecteds] expected-sequences]
            (let [expected (expected-reports expecteds)
                  actual   (reports-query-result :v4
                                                 ["=" "certname" "foo.local"]
                                                 {:order_by [[:report_format order]] :offset offset})]
              (is (= (order-events actual) (order-events expected))))))))))

(def data-seq (-> (slurp "./test-resources/puppetlabs/puppetdb/cli/export/reports-query-rows.json")
                  json/parse-string
                  keywordize-keys))

(def expected-result
  [{:hash "89944d0dcac56d3ee641ca9b69c54b1c15ef01fe",
    :puppet_version "3.7.3",
    :receive_time "2014-12-24T00:00:50Z",
    :report_format 4,
    :start_time "2014-12-24T00:00:49Z",
    :end_time "2014-12-24T00:00:49Z",
    :transaction_uuid "af4fb9ad-b267-4e0b-a295-53eba6b139b7",
    :status "changed",
    :environment "production",
    :configuration_version "1419379250",
    :certname "foo.com",
    :resource_events [{:new_value "Hi world",
                       :property "message",
                       :file "/home/wyatt/.puppet/manifests/site.pp",
                       :old_value "absent",
                       :line 3,
                       :resource_type "Notify",
                       :status "success",
                       :resource_title "hi",
                       :timestamp "2014-12-24T00:00:50Z",
                       :containment_path ["Stage[main]" "Main" "Notify[hi]"],
                       :message "defined 'message' as 'Hi world'"}
                      {:new_value "file",
                       :property "ensure",
                       :file "/home/wyatt/.puppet/manifests/site.pp",
                       :old_value "absent",
                       :line 7,
                       :resource_type "File",
                       :status "success",
                       :resource_title "/home/wyatt/Desktop/foo",
                       :timestamp "2014-12-24T00:00:50Z",
                       :containment_path
                       ["Stage[main]" "Main" "File[/home/wyatt/Desktop/foo]"],
                       :message
                       "defined content as '{md5}207995b58ba1956b97028ebb2f8caeba'"}]}
   {:hash "afe03ad7377e3c44d0f1f2abcf0834778759afff",
    :puppet_version "3.7.3",
    :receive_time "2014-12-24T00:01:12Z",
    :report_format 4,
    :start_time "2014-12-24T00:01:11Z",
    :end_time "2014-12-24T00:01:11Z",
    :transaction_uuid "f585ce01-0b5e-4ee3-b6d9-9d3ed6e42a05",
    :status "changed",
    :environment "production",
    :configuration_version "1419379250",
    :certname "bar.com",
    :resource_events [{:new_value "Hi world",
                       :property "message",
                       :file "/home/wyatt/.puppet/manifests/site.pp",
                       :old_value "absent",
                       :line 3,
                       :resource_type "Notify",
                       :status "success",
                       :resource_title "hi",
                       :timestamp "2014-12-24T00:01:12Z",
                       :containment_path ["Stage[main]" "Main" "Notify[hi]"],
                       :message "defined 'message' as 'Hi world'"}]}])

(deftest structured-data-seq
  (testing "structured data seq gets correct result"
    (is (= (r/structured-data-seq :v4 data-seq) expected-result)))
  (testing "laziness of collapsing fns"
    (let [ten-billion 10000000000]
      (is (= 10
             (count (take 10
                          (r/structured-data-seq
                            :v4 (mapcat
                                  (fn [certname]
                                    (take 4
                                          (-> (first data-seq)
                                              (assoc :certname certname :hash certname)
                                              repeat)))
                                  (map #(str "foo" % ".com") (range 0 ten-billion)))))))))))
