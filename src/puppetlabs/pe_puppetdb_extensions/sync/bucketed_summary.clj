(ns puppetlabs.pe-puppetdb-extensions.sync.bucketed-summary
  (:require [clj-time.core :as t]
            [clj-time.coerce :refer [to-date-time]]
            [honeysql.core :as hsql]
            [clojure.core.match :as cm]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.jdbc :as jdbc]))

;;; time utils

(defn utc-hour-of [time]
  (-> time
      to-date-time
      .hourOfDay
      .roundFloorCopy))

(defn next-clock-hour
  "Given a UTC date-time, return a date time at the beginning of the clock hour
  which follows it."
  [time]
  (t/plus (utc-hour-of time)
          (-> 1 t/hours)))

(defn normalize-time-stamp
  "Given a timestamp in some unknown format that clj-time understands, convert
  it to Joda time and to UTC."
  [ts]
  (-> ts
      to-date-time
      (t/to-time-zone (t/time-zone-for-offset 0))))


;;; grouping consecutive items in a seq

(defn take-while-consecutive [successor-fn coll]
  (letfn [(inner [last-item coll]
            (lazy-seq
             (when-let [s (seq coll)]
               (let [head (first s)]
                 (if (or (= last-item ::none)
                         (= head (successor-fn last-item)))
                   (cons head (inner head (next s)))
                   nil)))))]
    (inner ::none coll)))

;; Yes, this holds the head of run and has to realize all of run when executing
;; count. This is how partition-by does it.
(defn group-consecutive [successor-fn coll]
  (lazy-seq
   (when-let [s (seq coll)]
     (let [run (take-while-consecutive successor-fn coll)]
       (cons run (group-consecutive successor-fn
                                    (drop (count run) coll)))))))


;;; timespan seqs and query generation

;; These functions deal with timespans as [first-hour-inclusive
;; last-hour-exclusive] vectors. A timespan may be open on either end
;; by using :open.

(defn hourly-bucket-timestamps-to-timespans
  "Given a distinct, sorted seq of timestamps which are at the top of the
  hour (UTC), return a seq of timespans that represent groups of consecutive
  hours."
  [timestamps]
  (->> timestamps
       (group-consecutive next-clock-hour)
       (map (juxt first (comp next-clock-hour last)))))

(defn timespan-seq-complement
  "Given a sorted, exclusive seq of closed timespans, produce a seq of timespans
  that covers all other times."
  [closed-timespans]
  (concat [[:open (ffirst closed-timespans)]]
          (->> closed-timespans
               (partition 2 1)
               (map (fn [[[_ before-end] [after-start _]]]
                      [before-end after-start])))
          [[(last (last closed-timespans)) :open]]))

(defn pdb-query-condition-for-single-timespan
  "Create part of a pdb query for a single [start end] timespan vector."
  [[start end]]
  (let [start-expr [">=" "producer_timestamp" (str start)]
        end-expr ["<" "producer_timestamp" (str end)]]
    (cond
      (= start :open) end-expr
      (= end :open) start-expr
      :default ["and" start-expr end-expr])))

(defn pdb-query-condition-for-timespans [timespans]
  (some->> timespans
           (map pdb-query-condition-for-single-timespan)
           (apply vector "or")))

(defn to-sql-timespan-endpoint [t]
  (if (= t :open)
    "NULL"
    (str "'" t "'")))

(defn sql-query-condition-for-timespans [timespans]
  (->> timespans
       (map (fn [[start end]]
              (str "tstzrange("
                   (to-sql-timespan-endpoint start) ","
                   (to-sql-timespan-endpoint end) ","
                   "'[)') @> producer_timestamp")))
       (clojure.string/join " OR ")))

(defn sql-str [& args]
  (->> args
       (remove nil?)
       (clojure.string/join \newline)))

(defn generate-bucketed-summary-query [table timespans]
  ;; We need to do a little time zone dance here to get the output to be a
  ;; timestamptz.
  ;;
  ;; 1) In order to make an index that this query can use, all the grouping
  ;;    must be done in an explicit timezone (UTC here).
  ;;
  ;; 2) date_trunc returns a plain timestamp; we want it to be a timestamptz
  ;;    in UTC
  ;;
  ;; 3) The only way to convert a timestamp to a timestamptz is to call "AT
  ;;    TIME ZONE 'UTC'"; this interprets the timestamp as being in the
  ;;    current time zone, does whatever offset is required to get to the
  ;;    target, and gives you a timestamptz with the shifted time and the
  ;;    target timezone. (This is the *outer* "AT TIME ZONE 'UTC'" in the
  ;;    "SELECT" clause)
  ;;
  ;; 4) So, we need to set the timezone to UTC for this transaction. (that's
  ;;    the "LOCAL" bit)
  (sql-str "SELECT date_trunc('hour', producer_timestamp AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' "
           "         AS hour, "
           "       encode(md5_agg((date_part('epoch', timezone('UTC', producer_timestamp)) || certname)::bytea "
           "                       order by (date_part('epoch', timezone('UTC', producer_timestamp)) || certname)::bytea), "
           "              'hex') "
           "         AS hash "
           (str "FROM " (name table) " ")
           (when timespans (str "WHERE " (sql-query-condition-for-timespans timespans) " "))
           "GROUP BY date_trunc('hour', producer_timestamp AT TIME ZONE 'UTC')"))

(def cachable-entity-for-command
  {"store report" :reports
   "replace catalog" :catalogs})

;;; public

(defn bucketed-summary-query
  "Perform the bucketed summary query against scf-read-db for the given entity.
  Cache the results in cache-atom, which contains a map of the form {entity
  {hour-timestamp hash}}. This cache should be invalidated elsewhere as appropriate."
  [scf-read-db cache-atom entity]
  (let [timespans (some-> @cache-atom
                          (get entity)
                          keys
                          sort
                          hourly-bucket-timestamps-to-timespans
                          timespan-seq-complement)
        query-sql (generate-bucketed-summary-query entity timespans)
        result (jdbc/with-db-connection scf-read-db
                 (jdbc/with-db-transaction []
                   (jdbc/do-commands "SET LOCAL TIMEZONE TO 'UTC'")
                   (let [rows (jdbc/query-to-vec query-sql)]
                     (->> rows
                          (map (fn [{:keys [hour hash]}] [(to-date-time hour) hash]))
                          (into {})))))]
    (get (swap! cache-atom update entity #(merge % result))
         entity)))

(defn pdb-query-condition-for-buckets [timestamps]
  (->> timestamps
       hourly-bucket-timestamps-to-timespans
       pdb-query-condition-for-timespans))

(defn invalidate-cache-for-command
  "Process a message from the command response-mult, invalidating the hour
  bucket in cache-atom that corresponds to its producer-timestamp."
  [cache-atom {:keys [command producer-timestamp exception]}]
  (when (and command producer-timestamp (nil? exception))
    (when-let [entity (cachable-entity-for-command command)]
      (swap! cache-atom update entity dissoc (utc-hour-of producer-timestamp)))))
