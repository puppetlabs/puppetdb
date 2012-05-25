;; ## Population-wide queries
;;
;; Contains queries and metrics that apply across an entire population.

(ns com.puppetlabs.puppetdb.query.population
  (:use [com.puppetlabs.jdbc :only (query-to-vec table-count with-transacted-connection)]
        [com.puppetlabs.utils :only (quotient)]
        [metrics.gauges :only (gauge)]))

(defn correlate-exported-resources
  "Fetch a map of {exported-resource [#{exporting-nodes} #{collecting-nodes}]},
  to correlate the nodes exporting and collecting resources."
  []
  ;; TODO: This needs to only return results for active nodes
  (query-to-vec (str "SELECT DISTINCT exporters.type, exporters.title, "
                     "(SELECT certname FROM certname_catalogs WHERE catalog=exporters.catalog) AS exporter, "
                     "(SELECT certname FROM certname_catalogs WHERE catalog=collectors.catalog) AS collector "
                     "FROM catalog_resources exporters, catalog_resources collectors "
                     "WHERE exporters.resource=collectors.resource AND exporters.exported=true AND collectors.exported=false "
                     "ORDER BY exporters.type, exporters.title, exporter, collector ASC")))

(defn num-resources
  "The number of resources in the population"
  []
  {:post [(number? %)]}
  (-> (str "SELECT COUNT(*) AS c "
           "FROM certname_catalogs cc, catalog_resources cr, certnames c "
           "WHERE cc.catalog=cr.catalog AND c.name=cc.certname AND c.deactivated IS NULL")
      (query-to-vec)
      (first)
      :c))

(defn num-nodes
  "The number of unique certnames in the population"
  []
  {:post [(number? %)]}
  (-> "SELECT COUNT(*) AS c FROM certnames WHERE deactivated IS NULL"
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
                                          "(SELECT DISTINCT resource FROM catalog_resources cr, certname_catalogs cc, certnames c "
                                          " WHERE cr.catalog=cc.catalog AND cc.certname=c.name AND c.deactivated IS NULL) r"))
                       (first)
                       (:c))
        num-total  (num-resources)]
    (quotient (- num-total num-unique) num-total)))

;; ## Population-wide metrics

(def ns-str (str *ns*))
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
