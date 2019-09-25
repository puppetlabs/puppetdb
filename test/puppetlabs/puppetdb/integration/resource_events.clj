(ns puppetlabs.puppetdb.integration.resource-events
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clj-time.core :refer [now]]
            [clj-time.core :refer [now days plus]]
            [metrics.counters :as counters]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.cli.services :as pdb-services]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.partitioning :as partitioning])
  (:import [java.time LocalDateTime ZoneOffset]
           [java.sql Timestamp]))

(defn read-gc-count-metric []
  ;; metrics are global, so...
  (counters/value (:resource-events-purges pdb-services/admin-metrics)))

(deftest ^:integration resource-events-ttl
  (with-open [pg (int/setup-postgres)]
    (with-open [pdb (int/run-puppetdb pg {})
                ps (int/run-puppet-server [pdb] {})]
      (testing "Run agent once to populate database"
        (int/run-puppet-as "ttl-agent" ps pdb "notify { 'irrelevant manifest': }"))

      ;; You can't update the timestamp of a row inside the partitioned tables, because
      ;; that will violate the check constraint on the partition. To accomplish this, you
      ;; must insert a new row.
      (testing "update resource event timestamp to be at least two days old"
        (jdbc/with-db-connection (int/server-info pg)
          (let [new-datetime (-> (LocalDateTime/now)
                                 (.minusDays 2))
                new-timestamp (-> new-datetime
                                  (.toInstant ZoneOffset/UTC)
                                  Timestamp/from)]
            (partitioning/create-resource-events-partition (.toLocalDate new-datetime))
            (jdbc/call-with-query-rows
             ["select
                 report_id, certname_id, status, resource_type, resource_title, property,
                 new_value, old_value, message, file, line, containment_path, containing_class, corrective_change
               from resource_events"]
             (fn [rows]
               (doall
                (map (fn [row]
                       (let [hash-str (hash/resource-event-identity-pkey row)]
                         (jdbc/insert-multi! "resource_events"
                                             (list (assoc row :event_hash (sutils/munge-hash-for-storage hash-str)
                                                          :timestamp new-timestamp)))))
                     rows)))))))

      (testing "Verify we have resource events"
        (is (= 2 (count (int/pql-query pdb "events { timestamp > 0 }"))))))

    (testing "Sleep for one second to make sure we have a ttl to exceed"
      (Thread/sleep 10))

    (let [initial-gc-count (counters/value (:resource-events-purges pdb-services/admin-metrics))]
      ;; this TTL will be rounded to 1 day at execution time
      (with-open [pdb (int/run-puppetdb pg {:database {:resource-events-ttl "1h"}})]
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

        ;; should be one left - the one that's got a timestamp of today
        (testing "Verify that the resource events have been deleted"
          (is (= 1 (count (int/pql-query pdb "events { timestamp > 0 }")))))))))