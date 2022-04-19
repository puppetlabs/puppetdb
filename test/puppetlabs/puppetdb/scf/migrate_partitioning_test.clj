(ns puppetlabs.puppetdb.scf.migrate-partitioning-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.db :as tdb
             :refer [*db* clear-db-for-testing!
                     schema-info-map diff-schema-maps with-test-db]]
            [puppetlabs.puppetdb.scf.partitioning :as partitioning]
            [puppetlabs.puppetdb.scf.migrate-test :refer [apply-migration-for-testing! fast-forward-to-migration!]]
            [clojure.string :as str])
  (:import (java.time ZonedDateTime ZoneId)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(use-fixtures :each tdb/call-with-test-db)

(deftest migration-73-schema-diff
  (clear-db-for-testing!)
  (fast-forward-to-migration! 72)

  (let [before-migration (schema-info-map *db*)
        today (ZonedDateTime/now (ZoneId/of "UTC"))
        days-range (range -4 4)
        dates (map #(.plusDays today %) days-range)
        part-names (map #(str/lower-case (partitioning/date-suffix %)) dates)]
    (apply-migration-for-testing! 73)

    (is (= {:index-diff (into
                         [{:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_timestamp_idx"
                                       :index_keys ["\"timestamp\""]
                                       :type "btree"
                                       :unique? false
                                       :functional? false
                                       :is_partial false
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_containing_class_idx"
                                       :index_keys ["containing_class"]
                                       :type "btree"
                                       :unique? false
                                       :functional? false
                                       :is_partial false
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only nil
                           :right-only {:schema "public"
                                        :table "resource_events"
                                        :index "resource_events_pkey"
                                        :index_keys ["event_hash"]
                                        :type "btree"
                                        :unique? true
                                        :functional? false
                                        :is_partial false
                                        :primary? true
                                        :user "pdb_test"}
                           :same nil}
                          {:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_property_idx"
                                       :index_keys ["property"]
                                       :type "btree"
                                       :unique? false
                                       :functional? false
                                       :is_partial false
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_reports_id_idx"
                                       :index_keys ["report_id"]
                                       :type "btree"
                                       :unique? false
                                       :functional? false
                                       :is_partial false
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_resource_title_idx"
                                       :index_keys ["resource_title"]
                                       :type "btree"
                                       :unique? false
                                       :functional? false
                                       :is_partial false
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_status_for_corrective_change_idx"
                                       :index_keys ["status"]
                                       :type "btree"
                                       :unique? false
                                       :functional? false
                                       :is_partial true
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_resource_timestamp"
                                       :index_keys ["resource_type" "resource_title" "\"timestamp\""]
                                       :type "btree"
                                       :unique? false
                                       :functional? false
                                       :is_partial false
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only {:schema "public"
                                       :table "resource_events"
                                       :index "resource_events_unique"
                                       :index_keys ["report_id"
                                                    "resource_type"
                                                    "resource_title"
                                                    "property"]
                                       :type "btree"
                                       :unique? true
                                       :functional? false
                                       :is_partial false
                                       :primary? false
                                       :user "pdb_test"}
                           :right-only nil
                           :same nil}]
                         cat
                         (map
                          (fn [part-name]
                            (let [table-name (str "resource_events_" part-name)]
                              [{:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_timestamp_idx_" part-name)
                                             :index_keys ["\"timestamp\""]
                                             :type "btree"
                                             :unique? false
                                             :functional? false
                                             :is_partial false
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}
                               {:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_containing_class_idx_" part-name)
                                             :index_keys ["containing_class"]
                                             :type "btree"
                                             :unique? false
                                             :functional? false
                                             :is_partial false
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}
                               {:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_hash_" part-name)
                                             :index_keys ["event_hash"]
                                             :type "btree"
                                             :unique? true
                                             :functional? false
                                             :is_partial false
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}
                               {:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_property_idx_" part-name)
                                             :index_keys ["property"]
                                             :type "btree"
                                             :unique? false
                                             :functional? false
                                             :is_partial false
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}
                               {:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_reports_id_idx_" part-name)
                                             :index_keys ["report_id"]
                                             :type "btree"
                                             :unique? false
                                             :functional? false
                                             :is_partial false
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}
                               {:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_resource_title_idx_" part-name)
                                             :index_keys ["resource_title"]
                                             :type "btree"
                                             :unique? false
                                             :functional? false
                                             :is_partial false
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}
                               {:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_status_for_corrective_change_idx_" part-name)
                                             :index_keys ["status"]
                                             :type "btree"
                                             :unique? false
                                             :functional? false
                                             :is_partial true
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}
                               {:left-only nil
                                :right-only {:schema "public"
                                             :table table-name
                                             :index (str "resource_events_resource_timestamp_" part-name)
                                             :index_keys ["resource_type" "resource_title" "\"timestamp\""]
                                             :type "btree"
                                             :unique? false
                                             :functional? false
                                             :is_partial false
                                             :primary? false
                                             :user "pdb_test"}
                                :same nil}]))
                          part-names))
            :table-diff (into
                         [{:left-only nil
                           :right-only {:numeric_scale nil
                                        :column_default nil
                                        :character_octet_length nil
                                        :datetime_precision nil
                                        :nullable? "NO"
                                        :character_maximum_length nil
                                        :numeric_precision nil
                                        :numeric_precision_radix nil
                                        :data_type "bytea"
                                        :column_name "event_hash"
                                        :table_name "resource_events"}
                           :same nil}
                          {:left-only nil
                           :right-only {:numeric_scale nil
                                        :column_default nil
                                        :character_octet_length 1073741824
                                        :datetime_precision nil
                                        :nullable? "YES"
                                        :character_maximum_length nil
                                        :numeric_precision nil
                                        :numeric_precision_radix nil
                                        :data_type "text"
                                        :column_name "name"
                                        :table_name "resource_events"}
                           :same nil}]
                         cat
                         (map (fn [part-name]
                                (let [table-name (str "resource_events_" part-name)]
                                  [{:left-only nil
                                    :right-only {:numeric_scale 0
                                                 :column_default nil
                                                 :character_octet_length nil
                                                 :datetime_precision nil
                                                 :nullable? "NO"
                                                 :character_maximum_length nil
                                                 :numeric_precision 64
                                                 :numeric_precision_radix 2
                                                 :data_type "bigint"
                                                 :column_name "certname_id"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "containing_class"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length nil
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "ARRAY"
                                                 :column_name "containment_path"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length nil
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "boolean"
                                                 :column_name "corrective_change"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length nil
                                                 :datetime_precision nil
                                                 :nullable? "NO"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "bytea"
                                                 :column_name "event_hash"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default "NULL::character varying"
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "file"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale 0
                                                 :column_default nil
                                                 :character_octet_length nil
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision 32
                                                 :numeric_precision_radix 2
                                                 :data_type "integer"
                                                 :column_name "line"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "message"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "name"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "new_value"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "old_value"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "YES"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "property"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale 0
                                                 :column_default nil
                                                 :character_octet_length nil
                                                 :datetime_precision nil
                                                 :nullable? "NO"
                                                 :character_maximum_length nil
                                                 :numeric_precision 64
                                                 :numeric_precision_radix 2
                                                 :data_type "bigint"
                                                 :column_name "report_id"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "NO"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "resource_title"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "NO"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "resource_type"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length 1073741824
                                                 :datetime_precision nil
                                                 :nullable? "NO"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "text"
                                                 :column_name "status"
                                                 :table_name table-name}
                                    :same nil}
                                   {:left-only nil
                                    :right-only {:numeric_scale nil
                                                 :column_default nil
                                                 :character_octet_length nil
                                                 :datetime_precision 6
                                                 :nullable? "NO"
                                                 :character_maximum_length nil
                                                 :numeric_precision nil
                                                 :numeric_precision_radix nil
                                                 :data_type "timestamp with time zone"
                                                 :column_name "timestamp"
                                                 :table_name table-name}
                                    :same nil}]))
                              part-names))
            :constraint-diff (into
                              [{:left-only nil
                                :right-only {:constraint_name "event_hash IS NOT NULL"
                                             :table_name "resource_events"
                                             :constraint_type "CHECK"
                                             :initially_deferred "NO"
                                             :deferrable? "NO"}
                                :same nil}
                               {:left-only nil
                                :right-only {:constraint_name "resource_events_pkey"
                                             :table_name "resource_events"
                                             :constraint_type "PRIMARY KEY"
                                             :initially_deferred "NO"
                                             :deferrable? "NO"}
                                :same nil}
                               {:left-only {:constraint_name "resource_events_report_id_fkey"
                                            :table_name "resource_events"
                                            :constraint_type "FOREIGN KEY"
                                            :initially_deferred "NO"
                                            :deferrable? "NO"}
                                :right-only nil
                                :same nil}
                               {:left-only {:constraint_name "resource_events_unique"
                                            :table_name "resource_events"
                                            :constraint_type "UNIQUE"
                                            :initially_deferred "NO"
                                            :deferrable? "NO"}
                                :right-only nil
                                :same nil}]
                              cat
                              (map (fn [date-of-week]
                                     (let [part-name (str/lower-case (partitioning/date-suffix date-of-week))
                                           table-name (str "resource_events_" part-name)
                                           date-formatter (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ssx")
                                                                     (ZoneId/systemDefault))
                                           start-of-day (.format date-formatter
                                                                 (.truncatedTo date-of-week (ChronoUnit/DAYS)))
                                           end-of-day (.format date-formatter
                                                               (.plusDays (.truncatedTo date-of-week (ChronoUnit/DAYS)) 1))]
                                       [{:left-only nil
                                         :right-only {:constraint_name
                                                      (format "(((\"timestamp\" >= '%s'::timestamp with time zone) AND (\"timestamp\" < '%s'::timestamp with time zone)))"
                                                              start-of-day end-of-day)
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}
                                        {:left-only nil
                                         :right-only {:constraint_name "certname_id IS NOT NULL"
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}
                                        {:left-only nil
                                         :right-only {:constraint_name "event_hash IS NOT NULL"
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}
                                        {:left-only nil
                                         :right-only {:constraint_name "report_id IS NOT NULL"
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}
                                        {:left-only nil
                                         :right-only {:constraint_name "resource_title IS NOT NULL"
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}
                                        {:left-only nil
                                         :right-only {:constraint_name "resource_type IS NOT NULL"
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}
                                        {:left-only nil
                                         :right-only {:constraint_name "status IS NOT NULL"
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}
                                        {:left-only nil
                                         :right-only {:constraint_name "timestamp IS NOT NULL"
                                                      :table_name table-name
                                                      :constraint_type "CHECK"
                                                      :initially_deferred "NO"
                                                      :deferrable? "NO"}
                                         :same nil}]))
                                   dates))}
           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest migration-74-schema-diff
  (clear-db-for-testing!)
  (fast-forward-to-migration! 73)

  (let [before-migration (schema-info-map *db*)
        today (ZonedDateTime/now (ZoneId/of "UTC"))
        days-range (range -4 4)
        dates (map #(.plusDays today %) days-range)
        part-names (map #(str/lower-case (partitioning/date-suffix %)) dates)]
    (apply-migration-for-testing! 74)

    (is (= {:index-diff (into
                          []
                          cat
                          (map
                            (fn [part-name]
                              (let [table-name (str "reports_" part-name)]
                                [{:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_tx_uuid_expr_idx_" part-name)
                                               :index_keys ["(transaction_uuid::text)"]
                                               :type "btree"
                                               :unique? false
                                               :functional? true
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_cached_catalog_status_on_fail_" part-name)
                                               :index_keys ["cached_catalog_status"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial true
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_catalog_uuid_idx_" part-name)
                                               :index_keys ["catalog_uuid"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_certname_idx_" part-name)
                                               :index_keys ["certname"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_hash_expr_idx_" part-name)
                                               :index_keys ["encode(hash, 'hex'::text)"]
                                               :type "btree"
                                               :unique? true
                                               :functional? true
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_end_time_idx_" part-name)
                                               :index_keys ["end_time"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_environment_id_idx_" part-name)
                                               :index_keys ["environment_id"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_job_id_idx_" part-name)
                                               :index_keys ["job_id"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial true
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_noop_idx_" part-name)
                                               :index_keys ["noop"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial true
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "idx_reports_noop_pending_" part-name)
                                               :index_keys ["noop_pending"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial true
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "idx_reports_prod_" part-name)
                                               :index_keys ["producer_id"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "idx_reports_producer_timestamp_" part-name)
                                               :index_keys ["producer_timestamp"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "reports_status_id_idx_" part-name)
                                               :index_keys ["status_id"]
                                               :type "btree"
                                               :unique? false
                                               :functional? false
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only {:schema "public"
                                               :table table-name
                                               :index (str "idx_reports_producer_timestamp_by_hour_certname_" part-name)
                                               :index_keys ["date_trunc('hour'::text, timezone('UTC'::text, producer_timestamp))"
                                                            "producer_timestamp"
                                                            "certname"]
                                               :type "btree"
                                               :unique? false
                                               :functional? true
                                               :is_partial false
                                               :primary? false
                                               :user "pdb_test"}
                                  :same nil}
                                 {:left-only nil
                                  :right-only
                                  {:schema "public"
                                   :table table-name
                                   :index (str "idx_reports_compound_id_" part-name)
                                   :index_keys ["producer_timestamp" "certname" "hash"]
                                   :type "btree"
                                   :unique? false
                                   :functional? false
                                   :is_partial true
                                   :primary? false
                                   :user "pdb_test"}
                                  :same nil}]))
                            part-names))
            :table-diff (into
                          []
                          cat
                          (map (fn [part-name]
                                 (let [table-name (str "reports_" part-name)]
                                   [{:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length 1073741824
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "text"
                                                  :column_name "cached_catalog_status"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "uuid"
                                                  :column_name "catalog_uuid"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length 1073741824
                                                  :datetime_precision nil
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "text"
                                                  :column_name "certname"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length 1073741824
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "text"
                                                  :column_name "code_id"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length 1073741824
                                                  :datetime_precision nil
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "text"
                                                  :column_name "configuration_version"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "boolean"
                                                  :column_name "corrective_change"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision 6
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "timestamp with time zone"
                                                  :column_name "end_time"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale 0
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision 64
                                                  :numeric_precision_radix 2
                                                  :data_type "bigint"
                                                  :column_name "environment_id"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "bytea"
                                                  :column_name "hash"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale 0
                                                  :column_default "nextval('reports_id_seq'::regclass)"
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision 64
                                                  :numeric_precision_radix 2
                                                  :data_type "bigint"
                                                  :column_name "id"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length 1073741824
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "text"
                                                  :column_name "job_id"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "jsonb"
                                                  :column_name "logs"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "json"
                                                  :column_name "logs_json"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "jsonb"
                                                  :column_name "metrics"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "json"
                                                  :column_name "metrics_json"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "boolean"
                                                  :column_name "noop"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "boolean"
                                                  :column_name "noop_pending"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale 0
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision 64
                                                  :numeric_precision_radix 2
                                                  :data_type "bigint"
                                                  :column_name "producer_id"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision 6
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "timestamp with time zone"
                                                  :column_name "producer_timestamp"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length 1073741824
                                                  :datetime_precision nil
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "text"
                                                  :column_name "puppet_version"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision 6
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "timestamp with time zone"
                                                  :column_name "receive_time"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale 0
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision 16
                                                  :numeric_precision_radix 2
                                                  :data_type "smallint"
                                                  :column_name "report_format"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "jsonb"
                                                  :column_name "resources"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision 6
                                                  :nullable? "NO"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "timestamp with time zone"
                                                  :column_name "start_time"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale 0
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision 64
                                                  :numeric_precision_radix 2
                                                  :data_type "bigint"
                                                  :column_name "status_id"
                                                  :table_name table-name}
                                     :same nil}
                                    {:left-only nil
                                     :right-only {:numeric_scale nil
                                                  :column_default nil
                                                  :character_octet_length nil
                                                  :datetime_precision nil
                                                  :nullable? "YES"
                                                  :character_maximum_length nil
                                                  :numeric_precision nil
                                                  :numeric_precision_radix nil
                                                  :data_type "uuid"
                                                  :column_name "transaction_uuid"
                                                  :table_name table-name}
                                     :same nil}]))
                               part-names))
            :constraint-diff (into
                               [{:left-only {:constraint_name "certnames_reports_id_fkey"
                                             :table_name "certnames"
                                             :constraint_type "FOREIGN KEY"
                                             :initially_deferred "NO"
                                             :deferrable? "NO"}
                                 :right-only nil
                                 :same nil}]
                               cat
                               (map (fn [date-of-week]
                                      (let [part-name (str/lower-case (partitioning/date-suffix date-of-week))
                                            table-name (str "reports_" part-name)
                                            date-formatter (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ssx")
                                                                      (ZoneId/systemDefault))
                                            start-of-day (.format date-formatter
                                                                  (.truncatedTo date-of-week (ChronoUnit/DAYS)))
                                            end-of-day (.format date-formatter
                                                                (.plusDays (.truncatedTo date-of-week (ChronoUnit/DAYS)) 1))]
                                        [{:left-only nil
                                          :right-only {:constraint_name
                                                       (format "(((producer_timestamp >= '%s'::timestamp with time zone) AND (producer_timestamp < '%s'::timestamp with time zone)))"
                                                               start-of-day end-of-day)
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "certname IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "configuration_version IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "end_time IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "hash IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "id IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "producer_timestamp IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "puppet_version IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "receive_time IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "report_format IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name (str "reports_certname_fkey_" part-name)
                                                       :table_name table-name
                                                       :constraint_type "FOREIGN KEY"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name (str "reports_env_fkey_" part-name)
                                                       :table_name table-name
                                                       :constraint_type "FOREIGN KEY"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name (str "reports_prod_fkey_" part-name)
                                                       :table_name table-name
                                                       :constraint_type "FOREIGN KEY"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name (str "reports_status_fkey_" part-name)
                                                       :table_name table-name
                                                       :constraint_type "FOREIGN KEY"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}
                                         {:left-only nil
                                          :right-only {:constraint_name "start_time IS NOT NULL"
                                                       :table_name table-name
                                                       :constraint_type "CHECK"
                                                       :initially_deferred "NO"
                                                       :deferrable? "NO"}
                                          :same nil}]))
                                    dates))}
           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest migration-76-schema-diff
  (clear-db-for-testing!)
  (fast-forward-to-migration! 75)

  (let [before-migration (schema-info-map *db*)
        today (ZonedDateTime/now (ZoneId/of "UTC"))
        days-range (range -4 4)
        dates (map #(.plusDays today %) days-range)
        part-names (map #(str/lower-case (partitioning/date-suffix %)) dates)]
    (apply-migration-for-testing! 76)

    (is (= {:index-diff (into
                          []
                          cat
                          (map
                            (fn [part-name]
                              (let [table-name (str "reports_" part-name)]
                                 [{:left-only nil
                                  :right-only
                                  {:schema "public"
                                   :table table-name
                                   :index (str "idx_reports_id_" part-name)
                                   :index_keys ["id"]
                                   :type "btree"
                                   :unique? true
                                   :functional? false
                                   :is_partial false
                                   :primary? false
                                   :user "pdb_test"}
                                  :same nil}]))
                            part-names))
            :table-diff nil
            :constraint-diff nil}
           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest migration-79-schema-diff
  (clear-db-for-testing!)
  (fast-forward-to-migration! 78)

  (let [before-migration (schema-info-map *db*)
        today (ZonedDateTime/now (ZoneId/of "UTC"))
        days-range (range -4 4)
        dates (map #(.plusDays today %) days-range)
        part-names (map #(str/lower-case (partitioning/date-suffix %)) dates)]
    (apply-migration-for-testing! 79)

    (is (= {:index-diff (into
                          [{:left-only
                            {:schema "public"
                             :table "reports"
                             :index "reports_certname_idx"
                             :index_keys  ["certname"]
                             :type "btree"
                             :unique? false
                             :functional? false
                             :is_partial false
                             :primary? false
                             :user "pdb_test"}
                            :right-only nil
                            :same nil}
                           {:left-only nil
                            :right-only
                            {:schema "public"
                             :table "reports"
                             :index "idx_reports_certname_end_time"
                             :index_keys  ["certname" "end_time"]
                             :type "btree"
                             :unique? false
                             :functional? false
                             :is_partial false
                             :primary? false
                             :user "pdb_test"}
                            :same nil}]
                          cat
                          (map
                            (fn [part-name]
                              (let [table-name (str "reports_" part-name)]
                                 [{:left-only
                                   {:schema "public"
                                    :table table-name
                                    :index (str "reports_certname_idx_" part-name)
                                    :index_keys  ["certname"]
                                    :type "btree"
                                    :unique? false
                                    :functional? false
                                    :is_partial false
                                    :primary? false
                                    :user "pdb_test"}
                                   :right-only nil
                                   :same nil}
                                  {:left-only nil
                                   :right-only
                                   {:schema "public"
                                    :table table-name
                                    :index (str "idx_reports_certname_end_time_" part-name)
                                    :index_keys ["certname" "end_time"]
                                    :type "btree"
                                    :unique? false
                                    :functional? false
                                    :is_partial false
                                    :primary? false
                                    :user "pdb_test"}
                                   :same nil}]))
                            part-names))
            :table-diff nil
            :constraint-diff nil}
           (diff-schema-maps before-migration (schema-info-map *db*))))))
