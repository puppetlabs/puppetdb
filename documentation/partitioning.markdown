# Partitioning in PuppetDB

PuppetDB will use partitioning for its timeseries data, specifically `reports`
and `resource_events`. The primary goal of partitioning is to reduce the burden
of managing deleted data with `VACUUM`. Any query performance improvements are a
secondary concern.

Initial implementation is focused on the `resource_events` table.

## Partitions

Data will be partitioned on days (aka Julian calendar dates). Example:

May 1, 2019: `resource_events_2019_121`

December 31, 2020: `resource_events_2020_366`

When the TTL is reached, any partitions older than the TTL will be dropped.
The TTL is rounded to the nearest day.

Partitions will be created in accordance with the [PostgreSQL documentation](https://www.postgresql.org/docs/9.6/ddl-partitioning.html)

Partition implementation chosen is the inheritance-based approach supported in PostgreSQL 9.6+. In the future, declarative partitions
may be an option.

## Migration

During migration, the partition for the current date and &plusmn; 4 weeks will be created.

Existing data will be migrated into the new partitioned table similarly to the existing migrations. This can cause new partitions
to be created if necessary.

Caveat: the existing `resource_events` table will be renamed prior to the migration (ex. `resource_events_original`)
due to the nature of creating the partitions. This ensures we don't have a lengthy set of renames on the partitions themselves.

## Implementation

The base table is created with the same schema as the pre-partitioned table.

A database-side function is created to route `INSERT` statements to the proper partition by extracting the Julian date from
the `timestamp` column. This function is called via a `BEFORE INSERT` trigger on the base table.

Each partition has a `CHECK` constraint limiting it to the hours that apply for that day.

## Caveats

You cannot use `ON CONFLICT` modifiers on `INSERT INTO` statements with partitions. As a result, events are de-duplicated in Clojure
prior to being inserted into the database. This de-duplication uses a "first write wins" approach, where if two events have the same
`event_hash`, the entry with the oldest timestamp is written to the database.
