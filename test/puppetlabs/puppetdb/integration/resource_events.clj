(ns puppetlabs.puppetdb.integration.resource-events
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [metrics.counters :as counters]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.cli.services :as pdb-services]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.partitioning :as partitioning])

  (:import (java.time ZonedDateTime ZoneId)
           (java.sql Timestamp)))

(defn read-gc-count-metric []
  ;; metrics are global, so...
  (counters/value (:resource-events-purges @pdb-services/admin-metrics)))

(deftest ^:integration resource-events-ttl
  (with-open [pg (int/setup-postgres)]
    (with-open [pdb (int/run-puppetdb pg {})
                ps (int/run-puppet-server [pdb] {})]
      (testing "Run agent once to populate database"
        (int/run-puppet-as "ttl-agent" ps pdb "notify { 'irrelevant manifest': }"))

      (testing "Verify the agent run created an event"
        (is (= 1 (count (int/pql-query pdb "events { timestamp > 0 }")))))

      ;; You can't update the timestamp of a row inside the partitioned tables, because
      ;; that will violate the check constraint on the partition. To accomplish this, you
      ;; must insert a new row.
      (testing "update resource event timestamp to be at least two days old"
        (jdbc/with-db-connection (int/server-info pg)
          (let [new-datetime (-> (ZonedDateTime/now (ZoneId/of "UTC"))
                                 (.minusDays 2))
                new-timestamp (-> new-datetime
                                  (.toInstant)
                                  Timestamp/from)]
            (partitioning/create-resource-events-partition new-datetime)
            (jdbc/call-with-query-rows
             ["select
                 report_id, certname_id, status, resource_type, resource_title, property,
                 new_value, old_value, message, file, line, containment_path, containing_class, corrective_change
               from resource_events"]
             (fn [rows]
               (doseq [row rows]
                 (let [hash-str (hash/resource-event-identity-pkey row)]
                   (jdbc/insert-multi! "resource_events"
                                       (list (assoc row :event_hash (sutils/munge-hash-for-storage hash-str)
                                                    :timestamp new-timestamp))))))))))

      (testing "Verify we have resource events"
        (is (= 2 (count (int/pql-query pdb "events { timestamp > 0 }")))))

      (testing "Sleep for some time to make sure we have a ttl to exceed"
        (Thread/sleep 10))

      (let [initial-gc-count (counters/value (:resource-events-purges @pdb-services/admin-metrics))]
        ;; this TTL will be rounded to 1 day at execution time
        (with-open [pdb (int/run-puppetdb pg {:database
                                              {:resource-events-ttl "1h"
                                               :gc-interval "0.01"}})]
          (let [start-time (System/currentTimeMillis)]
            (loop []
              (cond
                (> (- (System/currentTimeMillis) start-time) tu/default-timeout-ms)
                (throw (ex-info "Timeout waiting for pdb gc to happen" {}))

                ;; Let GC run several times in order to drop all expired partitions
                (> (read-gc-count-metric) (+ 3 initial-gc-count))
                true

                :else
                (do
                  (Thread/sleep 250)
                  (recur)))))

          ;; should be one left - the one that's got a timestamp of today
          (testing "Verify that the resource events have been deleted"
            (is (= 1 (count (int/pql-query pdb "events { timestamp > 0 }"))))))))))

  (deftest ^:integration resource-events-zero-ttl
  (with-open [pg (int/setup-postgres)]
    (with-open [pdb (int/run-puppetdb pg {:database {:resource-events-ttl "0d"}})
                ps (int/run-puppet-server [pdb] {})]
      (testing "Run agent once to populate database"
        (int/run-puppet-as "ttl-agent" ps pdb "notify { 'irrelevant manifest': }"))

      (testing "Verify the agent run created no event"
        (is (= 0 (count (int/pql-query pdb "events { timestamp > 0 }"))))))))
