(ns puppetlabs.puppetdb.scf.partitioning
  "Handles all work related to database table partitioning"
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [schema.core :as s]
            [puppetlabs.i18n.core :refer [trs]])

  (:import (java.time LocalDateTime LocalDate ZoneId ZonedDateTime Instant)
           (java.time.temporal ChronoUnit)
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
    (instance? java.sql.Timestamp date) (-> date
                                            (.toInstant)
                                            (ZonedDateTime/ofInstant (ZoneId/of "UTC")))
    (instance? Instant date) (.atZone date (ZoneId/of "UTC"))
    :else (throw (ex-info (trs "Unhandled date type {0}" (type date)) {:date date}))))

(s/defn create-partition
  [base-table :- s/Str
   date-column :- s/Str
   date :- (s/cond-pre LocalDate LocalDateTime ZonedDateTime Instant)
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
    (apply jdbc/do-commands
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
  [^Instant date]
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
              iso-week-year full-table-name)])))

(defn create-reports-partition
  "Creates a partition in the reports table"
  [date]
  (create-partition
    "reports" "\"producer_timestamp\""
    date
    (fn [full-table-name iso-week-year]
      (let [alter-prefix "DO $$ BEGIN BEGIN "
            alter-suffix "; EXCEPTION WHEN duplicate_object THEN RETURN; END; END $$;"
            wrap-alter #(str alter-prefix % alter-suffix)]
        [(format "CREATE INDEX IF NOT EXISTS idx_reports_compound_id_%s ON %s USING btree (producer_timestamp, certname, hash) WHERE (start_time IS NOT NULL)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS idx_reports_noop_pending_%s ON %s USING btree (noop_pending) WHERE (noop_pending = true)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS idx_reports_prod_%s ON %s USING btree (producer_id)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS idx_reports_producer_timestamp_%s ON %s USING btree (producer_timestamp)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS idx_reports_producer_timestamp_by_hour_certname_%s ON %s USING btree (date_trunc('hour'::text, timezone('UTC'::text, producer_timestamp)), producer_timestamp, certname)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_cached_catalog_status_on_fail_%s ON %s USING btree (cached_catalog_status) WHERE (cached_catalog_status = 'on_failure'::text)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_catalog_uuid_idx_%s ON %s USING btree (catalog_uuid)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_certname_idx_%s ON %s USING btree (certname)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_end_time_idx_%s ON %s USING btree (end_time)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_environment_id_idx_%s ON %s USING btree (environment_id)"
                 iso-week-year full-table-name)
         (format "CREATE UNIQUE INDEX IF NOT EXISTS reports_hash_expr_idx_%s ON %s USING btree (encode(hash, 'hex'::text))"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_job_id_idx_%s ON %s USING btree (job_id) WHERE (job_id IS NOT NULL)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_noop_idx_%s ON %s USING btree (noop) WHERE (noop = true)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_status_id_idx_%s ON %s USING btree (status_id)"
                 iso-week-year full-table-name)
         (format "CREATE INDEX IF NOT EXISTS reports_tx_uuid_expr_idx_%s ON %s USING btree (((transaction_uuid)::text))"
                 iso-week-year full-table-name)
         (wrap-alter (format "ALTER TABLE ONLY %s ADD CONSTRAINT reports_certname_fkey_%s
               FOREIGN KEY (certname) REFERENCES certnames(certname) ON DELETE CASCADE"
                             full-table-name iso-week-year))
         (wrap-alter (format "ALTER TABLE ONLY %s ADD CONSTRAINT reports_env_fkey_%s
               FOREIGN KEY (environment_id) REFERENCES environments(id) ON DELETE CASCADE"
                             full-table-name iso-week-year))
         (wrap-alter (format "ALTER TABLE ONLY %s ADD CONSTRAINT reports_prod_fkey_%s
               FOREIGN KEY (producer_id) REFERENCES producers(id)"
                             full-table-name iso-week-year))
         (wrap-alter (format "ALTER TABLE ONLY %s ADD CONSTRAINT reports_status_fkey_%s
               FOREIGN KEY (status_id) REFERENCES report_statuses(id) ON DELETE CASCADE"
                             full-table-name iso-week-year))]))))
