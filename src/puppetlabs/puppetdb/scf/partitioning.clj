(ns puppetlabs.puppetdb.scf.partitioning
  "Handles all work related to database table partitioning"
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [schema.core :as s])
  (:import [java.time LocalDateTime LocalTime Year LocalDate]
           [java.time.temporal TemporalAdjusters WeekFields IsoFields]))

(defn- coerce-date
  [date]
  (if (instance? LocalDateTime date)
    (.toLocalDate date)
    date))

(defn day-suffix
  [date]
  (let [day (.getDayOfYear date)
        year (.getYear date)]
    (format "%d_%03d" year day)))

(s/defn create-partition
  [base-table :- s/Str
   date-column :- s/Str
   date :- java.time.LocalDate
   index-fn :- (s/fn-schema
                (s/fn :- [s/Str] [full-table-name :- s/Str
                                  iso-year-week :- s/Str]))]
  (let [start-of-day (.atStartOfDay date)
        start-of-next-day (.atStartOfDay (.plusDays date 1))

        table-name-suffix (day-suffix date)
        full-table-name (format "%s_%s" base-table table-name-suffix)]
    (apply jdbc/do-commands-outside-txn
           (concat [(format (str "CREATE TABLE IF NOT EXISTS %s ("
                                 " CHECK ( %s >= '%s'::timestamp AND %s < '%s'::timestamp )"
                                 ") INHERITS (%s)")
                            full-table-name
                            date-column start-of-day date-column start-of-next-day
                            base-table)]
                   (index-fn full-table-name table-name-suffix)))))

(defn create-resource-events-partition
  "Creates a partition in the resource_events table"
  [date]
  (create-partition
   "resource_events" "\"timestamp\""
   date
   (fn [full-table-name iso-week-year]
     [(format "CREATE INDEX IF NOT EXISTS resource_events_containing_class_idx_%s ON %s USING btree (containing_class)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_property_idx_%s ON %s USING btree (property)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_reports_id_idx_%s ON %s USING btree (report_id)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_resource_timestamp_%s ON %s USING btree (resource_type, resource_title, \"timestamp\")"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_resource_title_idx_%s ON %s USING btree (resource_title)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_status_for_corrective_change_idx_%s ON %s USING btree (status) WHERE corrective_change"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_status_idx_%s ON %s USING btree (status)"
              iso-week-year full-table-name)

      (format "CREATE INDEX IF NOT EXISTS resource_events_timestamp_idx_%s ON %s USING btree (\"timestamp\")"
              iso-week-year full-table-name)

      (format "CREATE UNIQUE INDEX IF NOT EXISTS resource_events_hash_%s ON %s (event_hash)"
              iso-week-year full-table-name)

      (format "ALTER TABLE ONLY %s DROP CONSTRAINT IF EXISTS resource_events_report_id_fkey_%s"
              full-table-name iso-week-year)

      (format "ALTER TABLE ONLY %s ADD CONSTRAINT resource_events_report_id_fkey_%s FOREIGN KEY (report_id) REFERENCES reports(id) ON DELETE CASCADE"
              full-table-name iso-week-year)])))
