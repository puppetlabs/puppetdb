(ns puppetlabs.puppetdb.query.population
  "Population-wide queries

   Contains queries and metrics that apply across an entire population."
  (:require [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [clojure.core.memoize :as mem]
            [metrics.gauges :refer [gauge-fn]]
            [clojure.java.jdbc :as sql]))

(defn first-query-result
  "Pick a key out of the first result of a query."
  [query k]
  (-> query
      query-to-vec
      first
      k))

(defn num-resources
  "The number of resources in the population"
  []
  {:post [(number? %)]}
  (-> "select reltuples::bigint as c from pg_class where relname='catalog_resources'"
      (first-query-result :c)))

(defn num-inactive-nodes
  "The number of expired/deactivated nodes"
  []
  {:post [(number? %)]}
  (-> "select count(*) as c from certnames_status where deactivated is not null or expired is not null"
      (first-query-result :c)))

(defn num-active-nodes
  "The number of unique certnames in the population"
  []
  {:post [(number? %)]}
  (-> "select count(*) as c from certnames_status where deactivated is null and expired is null"
      (first-query-result :c)))

(defn avg-resource-per-node
  "The average number of resources per node"
  []
  {:post [(number? %)]}
  (let [nresources (num-resources)
        nnodes (first-query-result "select count(*) as c from certnames" :c)]
    (/ nresources nnodes)))

(defn pct-resource-duplication*
  "What percentage of resources in the population are duplicates"
  []
  {:post [(number? %)]}
  (let [nresources (num-resources)
        ndistinct (-> "select count(*) as c from
                       (select distinct resource from catalog_resources) dist"
                      (first-query-result :c))]
    (if (zero? nresources)
      0
      (/ (- nresources ndistinct) nresources))))

(def pct-resource-duplication
  (mem/ttl pct-resource-duplication* :ttl/threshold 60000))

(defn backoff-on-failure
  "Make a wrapper for f to help with backoff. When everything is working, the
  wrapper just calls through to `f`. If `f` throws an exception, subsequent
  invocations of the wrapper will just return `nil` until `backoff-ms` have
  elapsed."
  [f backoff-ms]
  (let [last-fail-time (atom (- (System/currentTimeMillis) backoff-ms))]
    (fn [& args]
      (try
        (if (> (- (System/currentTimeMillis) @last-fail-time) backoff-ms)
          (apply f args)
          nil)
        (catch Exception _
          (reset! last-fail-time (System/currentTimeMillis))
          nil)))))

(def get-metrics-db-connection
  (backoff-on-failure sql/get-connection 5000))

(defn call-with-metrics-db-connection
  "Call `f` with an open connection to `db-spec` bound to `jdbc/*db*`. Unlike
  the regular db connection helpers, this version goes through
  `backoff-on-failure` to avoid long timeouts on every invocation when the
  database is down. It also doesn't open a transaction, as they aren't very
  important here."
  [db-spec f]
  (when-let [conn (get-metrics-db-connection db-spec)]
    (try
      (binding [jdbc/*db* (sql/add-connection db-spec conn)]
        (f))
      (finally
        (.close conn)))))

(defn initialize-population-metrics!
  "Initializes the set of population-wide metrics"
  [registry db]
  (gauge-fn registry ["num-resources"]
            (fn []
              (call-with-metrics-db-connection db num-resources)))
  (gauge-fn registry ["num-inactive-nodes"]
            (fn []
              (call-with-metrics-db-connection db num-inactive-nodes)))
  (gauge-fn registry ["num-active-nodes"]
            (fn []
              (call-with-metrics-db-connection db num-active-nodes)))
  (gauge-fn registry ["num-nodes"]
            (fn []
              (call-with-metrics-db-connection db num-active-nodes)))
  (gauge-fn registry ["avg-resources-per-node"]
            (fn []
              (call-with-metrics-db-connection db avg-resource-per-node)))
  (gauge-fn registry ["pct-resource-dupes"]
            (fn []
              (call-with-metrics-db-connection db pct-resource-duplication)))
  nil)
