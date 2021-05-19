---
title: "Summary-stats endpoint"
layout: default
---

# Summary-stats endpoint

> **Experimental Endpoint**: The summary-stats endpoint is designated
> as experimental. It may be altered or removed in a future release.

> **Warning**: This endpoint will execute a number of relatively expensive SQL
> commands against your database. It will not meaningfully impede performance
> of a running PDB instance, but the request may take several minutes to
> complete.

## `/pdb/admin/v1/summary-stats`

The `/summary-stats` endpoint is used to generate information about the way
your PDB installation is using postgres. Its intended purpose at this time is
to aid in diagnosis of support issues, though users may find it independently
useful.

### Response format

The response is a JSON map containing the following keys:

* `table_usage` (json): Postgres statistics related to table usage. Equivalent
  to the postgres query
      select * from pg_stat_user_tables;
* `index_usage` (json): Postgres statistics related to index usage. Equivalent
  to the postgres query
      select * from pg_stat_user_indexes;
* `database_usage` (json): Postgres statistics related to usage of the puppetdb
  database. Equivalent to the postgres query
      select now() as current_time, * from pg_stat_database where datname=current_database();
* `node_activity` (json): Counts of active and inactive nodes.
* `fact_path_counts_by_depth` (json): Number of fact paths for each fact path
  depth represented in the database.
* `num_shared_value_path_combos` (json): The number of fact value/fact path
  combinations that are shared across multiple nodes.
* `num_shared_name_value_combos` (json): The number of fact name/fact value
  combinations that are shared across multiple nodes.
* `num_unshared_value_path_combos` (json): The number of fact value/fact path
  combinations that are only present on one node.
* `num_unshared_name_value_combos` (json): The number of fact name/fact value
  combinations that are only present on one node.
* `num_times_paths_values_shared_given_sharing` (json): Across fact path/fact
  value combinations shared across multiple nodes, the 0th through 20th
  20-quantiles of the number of nodes sharing.
* `num_unique_fact_values_over_nodes` (json): Across all nodes, the 0th through
  20th 20-quantiles of the number of unique fact values.
* `string_fact_value_character_lengths` (json): 0th through 20th 20-quantiles of the
  character length of string-valued facts.
* `structured_fact_value_character_lengths` (json): 0th through 20th 20-quantiles of the
  character length of structured facts.
* `report_metric_size_dist` (json): 0th through 20th 20-quantiles of the
  character lengths of report metrics.
* `report_log_size_dist` (json): 0th through 20th 20-quantiles of the character
  lengths of report logs.
* `fact_values_by_type` (json): Number of fact values of each type present.
* `num_associated_factsets_over_fact_paths` (json): 0th through 20th
  20-quantiles of the number of nodes sharing a given fact.
* `num_resources_per_node` (json): 0th through 20th 20-quantiles over the
  number of resources per node.
* `num_resources_per_file` (json): 0th through 20th 20-quantiles over the
  number of resources per manifest.
* `num_distinct_edges_source_target` (json): Number of distinct source/target
  vertex pairs in the resource graph.
* `file_resource_per_catalog` (json): 0th through 20th 20-quantiles of the
  number of resources of type File over the most recent catalogs for all nodes.
* `file_resources_per_catalog_with_source` (json): 0th through 20th
  20-quantiles of the number of file resources with 'source' parameters across
  the most recent catalogs for all nodes.

### Parameters

This endpoint supports no parameters.
