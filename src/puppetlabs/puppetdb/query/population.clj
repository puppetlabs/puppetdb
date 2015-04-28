(ns puppetlabs.puppetdb.query.population
  "Population-wide queries

   Contains queries and metrics that apply across an entire population."
  (:require [puppetlabs.puppetdb.jdbc :refer [query-to-vec table-count with-transacted-connection]]
            [puppetlabs.kitchensink.core :refer [quotient]]
            [metrics.gauges :refer [gauge]]))

(defn num-resources
  "The number of resources in the population"
  []
  {:post [(number? %)]}
  (-> (str "SELECT COUNT(*) AS c "
           "FROM catalogs clogs, catalog_resources cr, certnames c "
           "WHERE clogs.id=cr.catalog_id AND c.certname=clogs.certname AND c.deactivated IS NULL AND c.expired IS NULL")
      (query-to-vec)
      (first)
      :c))

(defn num-nodes
  "The number of unique certnames in the population"
  []
  {:post [(number? %)]}
  (-> "SELECT COUNT(*) AS c FROM certnames WHERE deactivated IS NULL AND expired IS NULL"
      (query-to-vec)
      (first)
      :c))

(defn avg-resource-per-node
  "The average number of resources per node"
  []
  {:post [(number? %)]}
  (quotient (num-resources) (num-nodes)))

(defn pct-resource-duplication
  "What percentage of resources in the population are duplicates"
  []
  {:post [(number? %)]}
  (let [num-unique (-> (query-to-vec (str "SELECT COUNT(*) AS c FROM "
                                          "(SELECT DISTINCT resource FROM catalog_resources cr, catalogs clogs, certnames c "
                                          " WHERE cr.catalog_id=clogs.id AND clogs.certname=c.certname AND c.deactivated IS NULL AND c.expired IS NULL) r"))
                       (first)
                       (:c))
        num-total  (num-resources)]
    (quotient (- num-total num-unique) num-total)))

;; ## Population-wide metrics

;; This is pinned to the old namespace for backwards compatibility
(def ns-str "puppetlabs.puppetdb.query.population")
(def metrics (atom nil))

(defn population-gauges
  "Create a set of gauges that calculate population-wide metrics"
  [db]
  {:num-resources          (gauge [ns-str "default" "num-resources"]
                                  (with-transacted-connection db
                                    (num-resources)))
   :num-nodes              (gauge [ns-str "default" "num-nodes"]
                                  (with-transacted-connection db
                                    (num-nodes)))
   :avg-resources-per-node (gauge [ns-str "default" "avg-resources-per-node"]
                                  (with-transacted-connection db
                                    (avg-resource-per-node)))
   :pct-resource-dupes     (gauge [ns-str "default" "pct-resource-dupes"]
                                  (with-transacted-connection db
                                    (pct-resource-duplication)))})

(defn initialize-metrics
  "Initializes the set of population-wide metrics"
  [db]
  (compare-and-set! metrics nil (population-gauges db)))
