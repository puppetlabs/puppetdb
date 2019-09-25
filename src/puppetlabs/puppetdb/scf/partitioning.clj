(ns puppetlabs.puppetdb.scf.partitioning
  "Handles all work related to database table partitioning"
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [schema.core :as s])
  (:import [java.time LocalDateTime LocalDate ZoneId ZonedDateTime]
           [java.time.temporal ChronoUnit]
           (java.time.format DateTimeFormatter)))

(defn date-suffix
  [date]
  (let [formatter (.withZone (DateTimeFormatter/BASIC_ISO_DATE) (ZoneId/of "UTC"))]
    (.format date formatter)))

(defn to-zoned-date-time
  [date]
  (cond
    (instance? LocalDateTime date) (.atZone date (ZoneId/of "UTC"))
    (instance? LocalDate date) (.atStartOfDay date (ZoneId/of "UTC"))
    (instance? ZonedDateTime date) (.withZoneSameInstant date (ZoneId/of "UTC"))
    :else (throw (ex-info (str "Unhandled date type " (type date)) {:date date}))))

(s/defn create-partition
  [base-table :- s/Str
   date-column :- s/Str
   date :- (s/cond-pre LocalDate LocalDateTime ZonedDateTime)
   index-fn :- (s/fn-schema
                (s/fn :- [s/Str] [full-table-name :- s/Str
                                  iso-year-week :- s/Str]))]
  (let [date (to-zoned-date-time date)                      ;; guarantee a ZonedDateTime, so our suffix ends in Z
        start-of-day (-> date
                         (.truncatedTo (ChronoUnit/DAYS)))  ;; this is a ZonedDateTime
        start-of-next-day (-> start-of-day
                              (.plusDays 1))
        date-formatter (DateTimeFormatter/ISO_OFFSET_DATE_TIME)

        table-name-suffix (date-suffix date)
        full-table-name (format "%s_%s" base-table table-name-suffix)]
    (apply jdbc/do-commands-outside-txn
           (concat [(format (str "CREATE TABLE IF NOT EXISTS %s ("
                                 " CHECK ( %s >= TIMESTAMP WITH TIME ZONE '%s' AND %s < TIMESTAMP WITH TIME ZONE '%s' )"
                                 ") INHERITS (%s)")
                            full-table-name
                            ;; this will write the constraint in UTC. note: when you read this back from the database,
                            ;; you will get it in local time.
                            ;; example: constraint will have 2019-09-21T00:00:00Z but upon querying, you'll see 2019-09-21 17:00:00-07
                            ;; this is just the database performing i18n
                            date-column (.format start-of-day date-formatter) date-column (.format start-of-next-day date-formatter)
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
