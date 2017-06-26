---
title: "PuppetDB 5.0: Summary-stats endpoint"
layout: default
---

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
* `num_shared_fact_paths` (json): The number of fact paths that are
  shared across multiple nodes i.e. if one path is shared across three
  nodes, and another across five, then this will report 2 shared
  paths.
* `num_unshared_fact_paths` (json): The number of fact paths that are
  not shared across multiple nodes.
* `fact_path_sharing` (json): The 0th through 20th 20-quantiles of the
  number of nodes sharing each given fact_path.
* `string_fact_value_bytes` (json): 0th through 20th 20-quantiles of the
  byte length of string-valued facts.
* `structured_fact_value_bytes` (json): 0th through 20th 20-quantiles of the
  byte length of structured facts.
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
