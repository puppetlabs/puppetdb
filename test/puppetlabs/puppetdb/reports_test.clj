(ns puppetlabs.puppetdb.reports-test
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [cheshire.core :as json]
            [puppetlabs.puppetdb.cheshire :refer [dash-keys]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.reports :refer :all]
            [puppetlabs.puppetdb.testutils.reports
             :refer [munge-example-report-for-storage]]))

(let [report (munge-example-report-for-storage (:basic reports))]

  (deftest test-validate!

    (testing "should accept a valid v2 report"
      (is (= report (validate! 5 report))))

    (testing "should fail when a report is missing a key"
      (is (thrown-with-msg?
           IllegalArgumentException #"Report is missing keys: :certname$"
           (validate! 5 (dissoc report :certname)))))

    (testing "should fail when a resource event has the wrong data type for a key"
      (is (thrown-with-msg?
           IllegalArgumentException #":timestamp should be Datetime"
           (validate! 5 (assoc-in report [:resource-events 0 :timestamp] "foo")))))))

(deftest test-sanitize-events
  (testing "ensure extraneous keys are removed"
    (let [test-data {"containment_path"
                     ["Stage[main]"
                      "My_pg"
                      "My_pg::Extension[puppetdb:pg_stat_statements]"
                      "Postgresql_psql[create extension pg_stat_statements on puppetdb]"],
                     "new_value" "CREATE EXTENSION pg_stat_statements",
                     "message"
                     "command changed '' to 'CREATE EXTENSION pg_stat_statements'",
                     "old_value" nil,
                     "status" "success",
                     "line" 16,
                     "property" "command",
                     "timestamp" "2014-01-09T17:52:56.795Z",
                     "resource_type" "Postgresql_psql",
                     "resource_title" "create extension pg_stat_statements on puppetdb",
                     "file" "/etc/puppet/modules/my_pg/manifests/extension.pp"
                     "extradata" "foo"}
          santized (sanitize-events [test-data])
          expected [(dissoc test-data "extradata")]]
      (= santized expected))))

(deftest test-sanitize-report
  (testing "no action on valid reports"
    (let [test-data (dash-keys (clojure.walk/stringify-keys (:basic reports)))]
      (= (sanitize-report test-data) test-data))))
