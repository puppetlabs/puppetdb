(ns puppetlabs.puppetdb.integration.resource-events
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clj-time.core :refer [now]]
            [clj-time.core :refer [now days plus]]
            [metrics.counters :as counters]
            [puppetlabs.puppetdb.cli.services :as pdb-services]
            [puppetlabs.puppetdb.testutils :as tu]))

(defn read-gc-count-metric []
  ;; metrics are global, so...
  (counters/value (:resource-events-purges pdb-services/admin-metrics)))

(deftest ^:integration resource-events-ttl
  (with-open [pg (int/setup-postgres)]
    (with-open [pdb (int/run-puppetdb pg {})
                ps (int/run-puppet-server [pdb] {})]
      (testing "Run agent once to populate database"
        (int/run-puppet-as "ttl-agent" ps pdb "notify { 'irrelevant manifest': }"))

      (testing "Verify we have resource events"
        (is (= 1 (count (int/pql-query pdb "events { timestamp > 0 }"))))))

    (testing "Sleep for one second to make sure we have a ttl to exceed"
      (Thread/sleep 10))

    (let [initial-gc-count (counters/value (:resource-events-purges pdb-services/admin-metrics))]
      (with-open [pdb (int/run-puppetdb pg {:database {:resource-events-ttl "1ms"}})]
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

        (testing "Verify that the resource events have been deleted"
          (is (= 0 (count (int/pql-query pdb "events { timestamp > 0 }")))))))))
