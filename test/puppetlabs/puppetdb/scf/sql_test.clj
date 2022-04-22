(ns puppetlabs.puppetdb.scf.sql-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.testutils.db
    :refer [*db* schema-info-map diff-schema-maps]]
   [puppetlabs.puppetdb.testutils.nodes :refer [store-example-nodes]]
   [puppetlabs.puppetdb.testutils.services :as svc-utils :refer [with-puppetdb-instance]]))

; This MUST match the SQL run by resources/ext/cli/delete-reports.erb
(def delete-reports-sql-commands
  ["BEGIN TRANSACTION"
   "UPDATE certnames SET latest_report_id = NULL"
   "DO $$ DECLARE
        r RECORD;
    BEGIN
        FOR r IN (SELECT tablename FROM pg_tables WHERE tablename LIKE 'resource_events_%' OR tablename LIKE 'reports_%') LOOP
            EXECUTE 'DROP TABLE ' || quote_ident(r.tablename);
        END LOOP;
    END $$;"
   "COMMIT TRANSACTION"])

(deftest delete-reports-sql
  (with-puppetdb-instance
    (let [query (fn [url] (-> (svc-utils/query-url-str url)
                              (svc-utils/get)
                              :body))
          get-all-catalogs (fn [] (query "/catalogs"))
          get-all-factsets (fn [] (query "/factsets"))
          get-all-nodes (fn [] (query "/nodes"))
          get-all-reports (fn [] (query "/reports"))
          get-all-events (fn [] (query "/events"))]
      (store-example-nodes)

      (testing "verify there's data in reports and resource_events"
        (is (seq (get-all-reports)))
        (is (seq (get-all-events))))

      (let [before-truncation (schema-info-map *db*)
            catalogs (get-all-catalogs)
            factsets (get-all-factsets)
            nodes (get-all-nodes)]
        (apply jdbc/do-commands delete-reports-sql-commands)

        (testing "delete_reports.sql only deletes partitions"
          (let [no-right-or-same? (fn [diff]
                                    ;; Since we are deleting partitions, there shouldn't be anything new
                                    (is (nil? (get diff :right-only false)))
                                    (is (nil? (get diff :same false))))
                check-left-table-name (fn [{:keys [table table_name]}]
                                        ; The table name is in one of two keys
                                        (let [table-name (or table table_name)]
                                          ; Everything deleted should be from a partitioned table
                                          (is (or (re-matches #"reports_\d\d\d\d\d\d\d\dz" table-name)
                                                  (re-matches #"resource_events_\d\d\d\d\d\d\d\dz" table-name)))))
                check-diff (fn [diffs]
                             (doseq [diff diffs]
                               (no-right-or-same? diff)
                               (check-left-table-name (:left-only diff))))
                schema-diff (diff-schema-maps before-truncation (schema-info-map *db*))]

          (check-diff (:index-diff schema-diff))
          (check-diff (:table-diff schema-diff))
          (check-diff (:constaint-diff schema-diff))))

        (testing "reports and resource events were deleted"
          (is (empty?  (get-all-reports)))
          (is (empty? (get-all-events))))

        (testing "catalog, factsets, certnames were unaffected"

          (is (= catalogs
                 (get-all-catalogs)))
          (is (= factsets
                 (get-all-factsets)))

          (let [nil-entries (interleave [:latest_report_hash
                                         :report_environment
                                         :latest_report_corrective_change
                                         :latest_report_noop
                                         :latest_report_noop_pending
                                         :report_timestamp
                                         :latest_report_job_id
                                         :latest_report_status
                                         :cached_catalog_status]
                                        (repeat nil))
                clear-report-keys #(apply assoc % nil-entries)]
            (is (= (set (map clear-report-keys nodes))
                   (set (get-all-nodes))))))))))
