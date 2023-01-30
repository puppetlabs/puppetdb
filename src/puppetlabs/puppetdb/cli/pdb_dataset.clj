(ns puppetlabs.puppetdb.cli.pdb-dataset
  "Pg_restore and timeshift entries utility
   This command-line tool restores an empty database from a backup file (pg_dump generated file), then updates all the
   timestamps inside the database.
   It does this by calculating the period between the newest timestamp inside the file and the provided date.
   Then, every timestamp is shifted with that period.
   It accepts two parameters:
    - [Mandatory] -d / --dumpfile
      Path to the dumpfile that will be used to restore the database.
    - [Optional]-t / --shift-to-time
      Timestamp to which all timestamps from the dumpfile will be shifted after the restore.
      If it's not provided, the system's current timestamp will be used.
   !!! All timestamps are converted to a Zero timezone format. e.g timestamps like: 2015-03-26T10:58:51+10:00
   will become 2015-03-26T11:58:51Z !!!
   !!! If the time difference between the latest entry in the dumpfile and the time provided to timeshift-to is less
   than 24 hours this tool will fail !!!"

  (:require
    [clojure.java.shell :as shell]
    [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
    [puppetlabs.kitchensink.core :as kitchensink]
    [puppetlabs.puppetdb.utils :as utils :refer [println-err]]
    [puppetlabs.puppetdb.jdbc :as jdbc]
    [puppetlabs.puppetdb.scf.partitioning :as partitioning]
    [puppetlabs.puppetdb.time :refer [now to-timestamp]])
  (:import (java.lang Math)))

;; Argument parsing

(defn parse-timeshift-to
  [time-string]

  (let [parsed-time (to-timestamp time-string)]
    (when-not parsed-time
      (utils/throw-sink-cli-error "Error: time shift date must be in UTC format!"))
    parsed-time))

(defn validate-options
  [options]
  (let [parsed-time (if (:timeshift-to options)
                      (parse-timeshift-to (:timeshift-to options))
                      (now))]
    {:timeshift-to parsed-time
     :dumpfile     (:dumpfile options)}))

(defn validate-cli!
  [args]
  (let [specs [["-t" "--timeshift-to DATE" "Date in UTC format"]
               ["-d" "--dumpfile DUMPFILE" "Dumpfile"]]
        required [:dumpfile]]
    (utils/try-process-cli
      (fn []
        (-> args
            (kitchensink/cli! specs required)
            first
            validate-options)))))

(defn collect-pdbbox-config
  [args]
  (let [pdbbox-path (System/getenv "PDBBOX")
        ini-file (str pdbbox-path "/conf.d/pdb.ini")]
    (when (empty? pdbbox-path)
      (utils/throw-sink-cli-error "Error: PDBBOX env variable not set!"))
    (assoc args :config (:database (kitchensink/ini-to-map ini-file)))))

;; Time manipulation

(def miliseconds-in-day 86400000)
(def minutes-in-day 1440)
(def miliseconds-in-minute 60000)

(defn make-minutes-time-diff
  [max-time substract-time]
  (let [max-time-mili (.getTime max-time)
        substract-time-mili (.getTime substract-time)]
    (quot (- max-time-mili substract-time-mili) miliseconds-in-minute)))

(defn to-days
  [timestamp]
  (Math/round (float (/ (.getTime timestamp) miliseconds-in-day))))

(defn from-days-to-timestamp
  [instant]
  (to-timestamp (* instant miliseconds-in-day)))

(defn days-from-inst-vec
  [timestamp-vec column-name]
  (distinct (mapv #(to-days (column-name %)) timestamp-vec)))

;; Table updates

(defn create-copy-table
  [table]
  (jdbc/do-commands (str "CREATE TABLE " table "_copy (LIKE " table " INCLUDING ALL)")))

(defn copy-table
  [table]
  (jdbc/do-commands (str "INSERT INTO " table "_copy
                          SELECT * FROM " table)))

(defn create-partitions
  "Creates new partitions for reports and resource-events tables.
   In order to calculate the new date of the partitions and not request
   creation of a new partitions for every entry, we obtain an array of
   unique dates shifted with the period indicated by cli user."
  [time-diff-reports time-diff-resource-events]
  (let [prod-timestamp-vec (jdbc/query-to-vec "SELECT producer_timestamp FROM reports")
        timestamp-re (jdbc/query-to-vec "SELECT timestamp FROM resource_events")
        reports-partitions (days-from-inst-vec prod-timestamp-vec :producer_timestamp)
        resource-events-partitions (days-from-inst-vec timestamp-re :timestamp)
        time-diff-reports (Math/round (float (/ time-diff-reports minutes-in-day)))
        time-diff-resource-events (Math/round (float (/ time-diff-resource-events minutes-in-day)))
        reports-new-partitions (mapv #(+ time-diff-reports %) reports-partitions)
        resource-events-new-partitions (mapv #(+ time-diff-resource-events %) resource-events-partitions)]
    (doseq [day-reports reports-new-partitions
            day-re resource-events-new-partitions]
      (partitioning/create-reports-partition (from-days-to-timestamp day-reports))
      (partitioning/create-resource-events-partition (from-days-to-timestamp day-re)))))

(defn database-empty?
  []
  (let [schema_info (jdbc/query "SELECT 1 FROM information_schema.tables WHERE table_name = 'schema_migrations'")]
    (empty? schema_info)))

(defn restore-database
  [args]
  (let [dumpfile_path (:dumpfile args)]
    (println-err "Restoring database from backup")
    (shell/sh "pg_restore" "--role=postgres" "-U" "puppetdb" "--no-owner" "--no-acl" "-d" "puppetdb" dumpfile_path)
    (when (database-empty?)
      (utils/throw-sink-cli-error "Error: Restore failed!"))
    args))

(defn ensure-database-empty
  [_]
  (when-not (database-empty?)
    (utils/throw-sink-cli-error "Error: puppetdb database already exists and it isn't empty!")))

(defn update-simple-tables
  [table time-diff]
  (jdbc/do-commands (str "UPDATE " table " SET producer_timestamp = producer_timestamp + (" time-diff " * INTERVAL
  '1 minute'), timestamp = timestamp + (" time-diff " * INTERVAL '1 minute')")))

(defn add-reports-trigger
  []
  (jdbc/do-prepared "create function reports_insert1_trigger() returns trigger
                  language plpgsql
              as
              $$
              DECLARE
                     tablename varchar;
                   BEGIN
                     SELECT FORMAT('reports_%sZ',
                                   TO_CHAR(NEW.\"producer_timestamp\" AT TIME ZONE 'UTC', 'YYYYMMDD')) INTO tablename;
                     EXECUTE 'INSERT INTO ' || tablename || ' SELECT ($1).*'
                     USING NEW;
                     RETURN NULL;
                   END;
              $$;
              alter function reports_insert1_trigger() owner to puppetdb;
              CREATE TRIGGER reports_insert1_trigger
              BEFORE INSERT ON reports
                  FOR EACH ROW EXECUTE PROCEDURE reports_insert1_trigger()"))

(defn update-reports
  [time-diff]
  (create-copy-table "reports")
  (copy-table "reports")
  (jdbc/do-commands (str "UPDATE reports_copy
  SET producer_timestamp = producer_timestamp + (" time-diff " * INTERVAL '1 minute'),
  start_time = start_time + (" time-diff " * INTERVAL '1 minute'),
  end_time = end_time + (" time-diff " * INTERVAL '1 minute'),
  receive_time = receive_time + (" time-diff " * INTERVAL '1 minute')"))
  (jdbc/do-commands "DELETE FROM reports")
  (add-reports-trigger)
  (jdbc/do-commands "INSERT INTO reports SELECT * FROM reports_copy")
  (jdbc/do-commands "DROP FUNCTION reports_insert1_trigger() CASCADE")
  (jdbc/do-commands "DROP TABLE IF EXISTS reports_copy"))

(defn update-resource-events
  [time-diff]
  (create-copy-table "resource_events")
  (copy-table "resource_events")
  (jdbc/do-commands (str "UPDATE resource_events_copy SET timestamp = timestamp + (" time-diff " * INTERVAL '1 minute')"))
  (jdbc/do-commands "DELETE FROM resource_events")
  (jdbc/do-commands "INSERT INTO resource_events SELECT * FROM resource_events_copy")
  (jdbc/do-commands "DROP TABLE IF EXISTS resource_events_copy"))

(defn update-tables
  [args]
  (let [time-to-shift-to (to-timestamp (:timeshift-to args))
        max-time (:max (first (jdbc/query "SELECT max(producer_timestamp) FROM reports")))
        max-time-re (:max (first (jdbc/query "SELECT max(timestamp) FROM resource_events")))
        time-diff (make-minutes-time-diff time-to-shift-to max-time)
        time-diff-re (make-minutes-time-diff time-to-shift-to max-time-re)]
    (println-err "Updating data timestamps")
    (update-simple-tables "catalogs" time-diff)
    (update-simple-tables "factsets" time-diff)
    (create-partitions time-diff time-diff-re)
    (update-reports time-diff)
    (update-resource-events time-diff-re)))

(defn vacuum-db
  [_]
  (println-err "Running vacuum full on puppetdb database")
  (shell/sh "vacuumdb" "-f" "puppetdb" "-U" "postgres"))

(defn connect-to-db
  [args methods-array]
  (let [config (assoc (:config args)
                 :user "puppetdb"
                 :classname "org.postgresql.Driver"
                 :subprotocol "postgresql"
                 :pool-name "PDBDataSetPool"
                 :connection-timeout 3000
                 :rewrite-batched-inserts "true")]
    (binding [jdbc/*db* {:datasource (jdbc/make-connection-pool config)}]
      (mapv #(% args) methods-array))))

(defn -main
  [& args]

  (exit (run-cli-cmd #(do
                        (-> args
                            validate-cli!
                            collect-pdbbox-config
                            (connect-to-db [ensure-database-empty
                                            restore-database
                                            update-tables
                                            vacuum-db]))
                        0))))
