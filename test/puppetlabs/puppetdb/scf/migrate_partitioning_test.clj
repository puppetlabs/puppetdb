(ns puppetlabs.puppetdb.scf.migrate-partitioning-test
  (:require [puppetlabs.puppetdb.scf.migrate :refer :all]
            [clojure.test :refer :all]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.time :refer [ago days now to-timestamp]]
            [puppetlabs.puppetdb.testutils.db :as tdb
             :refer [*db* clear-db-for-testing!
                     schema-info-map diff-schema-maps]]
            [puppetlabs.puppetdb.scf.partitioning :as partitioning]
            [puppetlabs.puppetdb.scf.migrate-test :refer [apply-migration-for-testing! fast-forward-to-migration!]])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

(use-fixtures :each tdb/call-with-test-db)

(deftest migration-73-schema-diff
  (clear-db-for-testing!)
  (fast-forward-to-migration! 72)

  (let [before-migration (schema-info-map *db*)
        today (LocalDate/now)
        week-range (range -4 4)
        dates (map #(.plusWeeks today %) week-range)
        part-names (map #(partitioning/day-suffix %) dates)]
    (apply-migration-for-testing! 73)

    (is (= {:index-diff (into
                         [{:left-only nil
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
                                       :index "resource_events_unique"
                                       :index_keys ["report_id" "resource_type" "resource_title" "property"]
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
                               {:left-only {:constraint_name "resource_events_unique"
                                            :table_name "resource_events"
                                            :constraint_type "UNIQUE"
                                            :initially_deferred "NO"
                                            :deferrable? "NO"}
                                :right-only nil
                                :same nil}]
                              cat
                              (map (fn [date-of-week]
                                     (let [part-name (partitioning/day-suffix date-of-week)
                                           table-name (str "resource_events_" part-name)
                                           date-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
                                           start-of-day (.format date-formatter
                                                                       (.atStartOfDay date-of-week))
                                           end-of-day (.format date-formatter
                                                                       (.atStartOfDay (.plusDays date-of-week 1)))]
                                       [{:left-only nil
                                         :right-only {:constraint_name
                                                      (format "(((\"timestamp\" >= '%s'::timestamp without time zone) AND (\"timestamp\" < '%s'::timestamp without time zone)))"
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
                                         :right-only {:constraint_name
                                                      (str "resource_events_report_id_fkey_" part-name)
                                                      :table_name table-name
                                                      :constraint_type "FOREIGN KEY"
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
