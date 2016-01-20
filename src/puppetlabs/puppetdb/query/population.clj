(ns puppetlabs.puppetdb.query.population
  "Population-wide queries

   Contains queries and metrics that apply across an entire population."
  (:require [puppetlabs.puppetdb.jdbc :refer [query-to-vec
                                              table-count
                                              with-transacted-connection]]
            [puppetlabs.kitchensink.core :refer [quotient]]
            [metrics.gauges :refer [gauge]]
            [honeysql.core :as hcore]
            [honeysql.helpers :as hh]))

;;; Public

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

(defn pct-resource-duplication
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
;; ## Population-wide metrics

;; This is pinned to the old namespace for backwards compatibility
(def ns-str "puppetlabs.puppetdb.query.population")
(def metrics (atom nil))

(defn population-gauges
  "Create a set of gauges that calculate population-wide metrics"
  [db]
  {:num-resources (gauge [ns-str "default" "num-resources"]
                         (with-transacted-connection db
                           (num-resources)))
   :num-active-nodes (gauge [ns-str "default" "num-active-nodes"]
                            (with-transacted-connection db
                              (num-active-nodes)))
   :num-inactive-nodes (gauge [ns-str "default" "num-inactive-nodes"]
                              (with-transacted-connection db
                                (num-inactive-nodes)))
   ;; duplicated metric for backward-compatibility
   :num-nodes (gauge [ns-str "default" "num-nodes"]
                     (with-transacted-connection db
                       (num-active-nodes)))
   :avg-resources-per-node (gauge [ns-str "default" "avg-resources-per-node"]
                                  (with-transacted-connection db
                                    (avg-resource-per-node)))
   :pct-resource-dupes (gauge [ns-str "default" "pct-resource-dupes"]
                              (with-transacted-connection db
                                (pct-resource-duplication)))})

(defn initialize-metrics
  "Initializes the set of population-wide metrics"
  [db]
  (compare-and-set! metrics nil (population-gauges db)))
