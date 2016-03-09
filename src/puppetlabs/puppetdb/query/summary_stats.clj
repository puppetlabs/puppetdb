(ns puppetlabs.puppetdb.query.summary-stats
  (:require [clojure.string :as str]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.meta.version :as v]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as ks]))

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

   :num_shared_value_path_combos
   "select count(f.fact_value_id)
    from (select fact_value_id
    from facts
    group by fact_path_id, fact_value_id
    having count(*) > 1) as f"

   :num_shared_name_value_combos
   "select count(f.name)
    from (select name, fact_value_id
    from facts
    inner join fact_paths fp on facts.fact_path_id = fp.id
    group by fact_value_id, name
    having count(*) > 1) as f"

   :num_unshared_value_path_combos
   "select count(f.fact_value_id)
    from (select fact_value_id
    from facts
    group by fact_path_id, fact_value_id
    having count(*) = 1) as f"

   :num_unshared_name_value_combos
   "select count(f.name)
    from (select name, fact_value_id
    from facts
    inner join fact_paths fp on facts.fact_path_id = fp.id
    group by fact_value_id, name
    having count(*) = 1) as f"

   :num_times_paths_values_shared_given_sharing
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by c) quantiles
    from
    (select fv.id, vt.type, f.c
    from fact_values fv inner join value_types vt on fv.value_type_id = vt.id
    inner join
    (select fact_value_id, count(*) as c
    from facts
    group by fact_path_id, fact_value_id
    having count(*) > 1
    order by c desc) as f on f.fact_value_id = fv.id
    order by f.c) foo"

   :num_unique_fact_values_over_nodes
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by c) quantiles
    from
    (select count(*) as c, factset_id from
    (select factset_id, f.fact_value_id, f.fact_path_id from facts f
    inner join (select fact_value_id, fact_path_id from facts
    group by fact_path_id, fact_value_id having count(*) = 1) foo
    on f.fact_value_id=foo.fact_value_id and f.fact_path_id=foo.fact_path_id) bar
    group by factset_id) baz"

   :string_fact_value_bytes
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by length) quantiles
    from
    (select pg_column_size(value_string) as length from fact_values
    where value_type_id = 0) foo"

   :structured_fact_value_bytes
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by length) quantiles
    from
    (select pg_column_size(value) as length from fact_values
    where value_type_id = 5) foo"

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

   :fact_values_by_type
   "select vt.type, count(*)
    from fact_values fv inner join value_types vt on fv.value_type_id = vt.id
    group by vt.type"

   :num_associated_factsets_over_fact_paths
   "select percentile_cont(Array[0, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
    0.35, 0.40, 0.45, 0.50, 0.55, 0.60,
    0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1])
    within group (order by c) quantiles
    from
    (select fact_path_id, count(*) as c
    from facts
    group by fact_path_id
    order by c) foo"

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
          json/generate-pretty-string))))
