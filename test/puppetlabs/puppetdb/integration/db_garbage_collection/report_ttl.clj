(ns puppetlabs.puppetdb.integration.db-garbage-collection.report-ttl
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.cheshire :as json]
            [metrics.counters :as counter]
            [puppetlabs.puppetdb.cli.services :as pdb-services]
            [metrics.counters :as counters]
            [puppetlabs.puppetdb.testutils :as tu]))

(defn read-gc-count-metric []
  ;; metrics are global, so...
  (counters/value (:report-purges pdb-services/admin-metrics)))

(deftest ^:integration report-ttl
  (with-open [pg (int/setup-postgres)]
    (with-open [pdb (int/run-puppetdb pg {})
                ps (int/run-puppet-server [pdb] {})]
      (testing "Run agent once to populate database"
        (int/run-puppet-as "ttl-agent" ps pdb "notify { 'irrelevant manifest': }"))

      (testing "Verify we have a report"
        (is (= 1 (count (int/pql-query pdb "reports { certname = 'ttl-agent' }"))))))

    (testing "Sleep for one second to make sure we have a ttl to exceed"
      (Thread/sleep 1000))

    (let [initial-gc-count (counters/value (:report-purges pdb-services/admin-metrics))]
      (with-open [pdb (int/run-puppetdb pg {:database {:report-ttl "1s"}})
                  ps (int/run-puppet-server [pdb] {})]
        (let [start-time (System/currentTimeMillis)]
          (loop []
            (cond
              (> (- (System/currentTimeMillis) start-time) tu/default-timeout-ms)
              (throw (ex-info "Timeout waiting for pdb gc to happen" {}))

              (> (read-gc-count-metric) initial-gc-count)
              true ;; gc happened

              :default
              (do
                (Thread/sleep 250)
                (recur)))))

        (testing "Verify that the report has been deleted"
          (is (= 0 (count (int/pql-query pdb "reports { certname = 'ttl-agent' }")))))))))
