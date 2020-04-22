(ns puppetlabs.puppetdb.scf.sql-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.utils :refer [update-vals]]
   [puppetlabs.puppetdb.testutils.db
    :refer [*db* schema-info-map diff-schema-maps]]
   [puppetlabs.puppetdb.testutils.nodes :refer [store-example-nodes]]
   [puppetlabs.puppetdb.testutils.catalogs :refer [munge-catalog]]
   [puppetlabs.puppetdb.testutils.facts :refer [munge-facts]]
   [puppetlabs.puppetdb.testutils.services :as svc-utils :refer [with-puppetdb-instance]]))

; This MUST match the SQL run by resources/ext/cli/delete-reports.erb
(def delete-reports-sql-commands
  ["BEGIN TRANSACTION"
   "ALTER TABLE certnames DROP CONSTRAINT IF EXISTS certnames_reports_id_fkey"
   "UPDATE certnames SET latest_report_id = NULL"
   "TRUNCATE TABLE reports CASCADE"
   "ALTER TABLE certnames ADD CONSTRAINT \"certnames_reports_id_fkey\" FOREIGN KEY (latest_report_id) REFERENCES reports(id) ON DELETE SET NULL"
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

        (testing "delete_reports.sql doesn't change db schema"
          (is (= {:index-diff nil, :table-diff nil, :constraint-diff nil}
                 (diff-schema-maps before-truncation (schema-info-map *db*)))))

        (testing "reports and resource events were deleted"
          (is (empty?  (get-all-reports)))
          (is (empty? (get-all-events))))

        (testing "catalog, factsets, certnames were unaffected"

          (is (= catalogs
                 (get-all-catalogs)))
          (is (= factsets
                 (get-all-factsets)))

          (let [nil-report-keys
                (partial map
                         #(update-vals
                            %
                            [:latest_report_hash :report_environment :latest_report_corrective_change
                             :latest_report_noop :latest_report_noop_pending :report_timestamp
                             :latest_report_job_id :latest_report_status :cached_catalog_status]
                            (constantly nil)))]
            (is (= (set (nil-report-keys nodes))
                   (set (get-all-nodes))))))))))
