(ns puppetlabs.puppetdb.scf.partitioning
  "Handles all work related to database table partitioning"
  (:require
   [clojure.string :refer [lower-case]]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [schema.core :as s])
  (:import (java.time LocalDateTime LocalDate ZoneId ZonedDateTime Instant)
           (java.time.temporal ChronoUnit)
           (java.time.format DateTimeFormatter)))

(defn get-partition-names
  "Return all partition names given the parent table name"
  [table]
    (->> ["SELECT inhrelid::regclass AS child
            FROM pg_catalog.pg_inherits
            WHERE inhparent = ?::regclass;"
          table]
         jdbc/query-to-vec
         (map :child)
         (map str)))

(defn get-temporal-partitions
  "Returns a vector of {:table full-table-name :part partition-key}
  values for all the existing partitions associated with the
  name-prefix, e.g. request for \"reports\" might produce a vector of
  maps like {:table \"reports_20200802z\" :part \"20200802z\"}."
  [parent-table]
  (mapv (fn [tablename]
          {:table tablename
           :part (subs tablename (- (count tablename) 9))})
        (get-partition-names parent-table)))

(defn date-suffix
  [date]
  (let [formatter (.withZone DateTimeFormatter/BASIC_ISO_DATE (ZoneId/of "UTC"))]
    (lower-case (.format date formatter))))

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
  [parent-table :- s/Str
   prefix :- s/Str
   date :- (s/cond-pre LocalDate LocalDateTime ZonedDateTime Instant java.sql.Timestamp)]
  (let [date (to-zoned-date-time date)                      ;; guarantee a ZonedDateTime, so our suffix ends in Z
        start-of-day (-> date
                         (.truncatedTo (ChronoUnit/DAYS)))  ;; this is a ZonedDateTime
        start-of-next-day (-> start-of-day
                              (.plusDays 1))
        date-formatter DateTimeFormatter/ISO_OFFSET_DATE_TIME

        table-name-suffix (date-suffix date)
        full-table-name (format "%s_%s" prefix table-name-suffix)]
    (jdbc/do-commands
      (format (str "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s "
                   "FOR VALUES FROM ('%s') TO ('%s')")
              full-table-name
              parent-table
              ;; this will write the constraint in UTC. note: when you read this back from the database,
              ;; you will get it in local time.
              ;; example: constraint will have 2019-09-21T00:00:00Z but upon querying, you'll see 2019-09-21 17:00:00-07
              ;; this is just the database performing l10n
              ;;
              ;; NOTE: in PostgreSQL 11 the 'VALUES FROM' entries must be literal
              ;; constants (we can't use any postgres cast functions), so we're
              ;; relying on the date-formatter's ISO_OFFSET_DATE_TIME format to
              ;; present them in a timestamp with timezone format.
              (.format start-of-day date-formatter)
              (.format start-of-next-day date-formatter)))))

(defn create-resource-events-partition
  "Creates a partition in the resource_events table"
  [^Instant date]
  (create-partition "resource_events_historical" "resource_events" date))

(defn create-reports-partition
  "Creates a partition in the reports table"
  [date]
  (create-partition "reports_historical" "reports" date))
