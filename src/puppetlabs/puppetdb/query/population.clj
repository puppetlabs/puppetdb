(ns puppetlabs.puppetdb.query.population
  "Population-wide queries

   Contains queries and metrics that apply across an entire population."
  (:require [puppetlabs.puppetdb.jdbc :refer [query-to-vec
                                              table-count
                                              with-transacted-connection]]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [clojure.core.memoize :as mem]
            [puppetlabs.kitchensink.core :refer [quotient]]
            [metrics.gauges :refer [gauge-fn]]
            [honeysql.core :as hcore]
            [honeysql.helpers :as hh]))

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
  (-> "select count(*) as c from certnames where deactivated is not null or expired is not null"
      (first-query-result :c)))

(defn num-active-nodes
  "The number of unique certnames in the population"
  []
  {:post [(number? %)]}
  (-> "select count(*) as c from certnames where deactivated is null and expired is null"
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

(defn initialize-population-metrics!
  "Initializes the set of population-wide metrics"
  [registry db]
  (gauge-fn registry ["num-resources"]
            (fn []
              (with-transacted-connection db
                (num-resources))))
  (gauge-fn registry ["num-inactive-nodes"]
            (fn []
              (with-transacted-connection db
                (num-inactive-nodes))))
  (gauge-fn registry ["num-active-nodes"]
            (fn []
              (with-transacted-connection db
                (num-active-nodes))))
  (gauge-fn registry ["num-nodes"]
            (fn []
              (with-transacted-connection db
                (num-active-nodes))))
  (gauge-fn registry ["avg-resources-per-node"]
            (fn []
              (with-transacted-connection db
                (avg-resource-per-node))))
  (gauge-fn registry ["pct-resource-dupes"]
            (fn []
              (with-transacted-connection db
                (pct-resource-duplication))))
  nil)
