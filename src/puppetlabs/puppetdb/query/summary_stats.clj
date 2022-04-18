(ns puppetlabs.puppetdb.query.summary-stats
  (:require
   [puppetlabs.kitchensink.core :as ks]
   [puppetlabs.puppetdb.http :as http]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.meta.version :as v]))

(def metadata-queries
  {:table_usage
   "select * from pg_stat_user_tables"

   :index_usage
   "select * from pg_stat_user_indexes"

   :database_usage
   "select now() as current_time, *
    from pg_stat_database where datname=current_database()"

   :node_activity
   "select count(*), expired is null and deactivated is null as active
    from certnames group by active"

   :fact_path_counts_by_depth
   "select depth, count(*) as count
    from fact_paths
    group by depth
    order by depth"

   :report_metric_size_dist
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by length) quantiles
    from
    (select pg_column_size(metrics) as length from reports
    where metrics is not null) foo"

   :report_log_size_dist
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by length) quantiles
    from
    (select pg_column_size(logs) as length from reports
    where logs is not null) foo"

   :num_resources_per_node
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by c) quantiles
    from
    (select count(*) as c from catalog_resources group by certname_id) foo"

   :num_resources_per_file
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by c) quantiles
    from
    (select count(*) as c from catalog_resources where file is not null group by file) foo"

   :file_resources_per_catalog
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by c) quantiles
    from
    (select count(*) as c, certname_id from catalog_resources where type='File'
    group by certname_id) foo"

   :file_resources_per_catalog_with_source
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by c) quantiles
    from
    (select count(*) as c, certname_id from catalog_resources
    inner join resource_params on
    catalog_resources.resource=resource_params.resource
    where type='File' and name='source'
    group by certname_id) foo"

   :num_distinct_edges_source_target
   "select count(distinct (source, target)) from edges"})

(defn collect-metadata
  [get-shared-globals]
  (let [{:keys [scf-read-db] :as db} (get-shared-globals)]
    (jdbc/with-transacted-connection scf-read-db
      (-> (ks/mapvals jdbc/query-to-vec metadata-queries)
          (assoc :version (v/version))
          http/json-response))))
