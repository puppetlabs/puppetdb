---
title: "PuppetDB: Release notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

[configure-postgres]: configure.markdown#using-postgresql
[drop-joins]: api/query/v4/query.markdown#experimental-query-optimization
[facts]: api/query/v4/facts.markdown
[fact-contents]: api/query/v4/fact-contents.markdown

---

# PuppetDB: Release notes

## PuppetDB 7.5.0

Released July 20 2021

### New features and improvements

* The [query optimizer][drop-joins] that attempts to drop unneeded
  joins is now enabled by default, but that can be changed by setting
  the [`PDB_QUERY_OPTIMIZE_DROP_UNUSED_JOINS`][drop-joins] environment
  variable. ([PDB-5131](https://tickets.puppetlabs.com/browse/PDB-5131))

### Bug fixes

* The `to_string` function should now be allowed in [facts][facts] and
  [fact-contents][fact-contents] query fields.
  ([PDB-5104](https://tickets.puppetlabs.com/browse/PDB-5104))

* Queries that include a `limit`, `offset`, or `order_by`, in a
  subquery that uses `from` like this:

  ```
  ["from", "nodes",
   ["in", "certname",
    ["from", "reports", ["extract", "certname"],
     ["limit", 1]]]]
  ```

  should no longer crash with an error that looks like this:

  > 'type' is not a queryable object for nodes...

  ([PDB-5026](https://tickets.puppetlabs.com/browse/PDB-5026))

* The `delete-reports` subcommand now restarts the puppetdb service
  after deleting reports.
  ([PDB-5142](https://tickets.puppetlabs.com/browse/PDB-5142))

### Contributors

Andrei Filipovici, Austin Blatt, Ethan J. Brown, Filipovici-Andrei,
Heston Hoffman, Maggie Dreyer, Oana Tanasoiu, Rob Browning, and
Sebastian Miclea

## PuppetDB 7.4.1

Released June 24 2021.

This release contains a fix for
[CVE-2021-27021](https://puppet.com/security/cve/cve-2021-27021/). As part of
the mitigation of this CVE you should [create and configure a read only
user][configure-postgres]. If you are using Puppet Enterprise, or you are
managing your postgres database using the Open Source module (version 7.9.0+)
the read only user will be configured automatically.

Related patches, addressing the vulnerability:

  * (PDB-5138) validate-dotted-field: anchor regexp
    [c146e624d230f7410fb648d58ae28c0e3cd457a2](https://github.com/puppetlabs/puppetdb/commit/c146e624d230f7410fb648d58ae28c0e3cd457a2)
  * (PDB-5138) quote-projections: quote all projections
    [f8dc81678cf347739838e42cc1c426d96406c266](https://github.com/puppetlabs/puppetdb/commit/f8dc81678cf347739838e42cc1c426d96406c266)
  * (PDB-5138) Strictly validate function AST
    [72bd137511487643a3a6236ad9e72a5dd4a6fadb](https://github.com/puppetlabs/puppetdb/commit/72bd137511487643a3a6236ad9e72a5dd4a6fadb)

A patch to ensure PuppetDB logs if the query user's permissions are
insufficiently restricted:

  * (PDB-5145) Detect and log ERROR level messages if read-only user is misconfigured
    [4077d580913c45e471e12cecc9f90df62d95f38f](https://github.com/puppetlabs/puppetdb/commit/4077d580913c45e471e12cecc9f90df62d95f38f)

### Security fixes

* Fixed an issue where someone with the ability to query PuppetDB could
  arbitrarily write, update, or delete data
  [CVE-2021-27021](https://puppet.com/security/cve/cve-2021-27021/)
  [PDB-5138](https://tickets.puppetlabs.com/browse/PDB-5138)


### New features and improvements

* Significantly reduced the memory usage by the puppetdb terminus to process
  commands. [PDB-5107](https://tickets.puppetlabs.com/browse/PDB-5107)
* Some command processing operations should require less work and require fewer
  round trips to the database.
  [PDB-5128](https://tickets.puppetlabs.com/browse/PDB-5128)
* If the read-only user has database permissions that it does not need,
  PuppetDB will log errors.
  [PDB-5145](https://tickets.puppetlabs.com/browse/PDB-5145)


### Bug Fixes

* (PE only) Fixed an issue causing unnecessary factset sync
  [PDB-5021](https://tickets.puppetlabs.com/browse/PDB-5021)
* Lock timeouts will be parsed correctly now. Previously, if a lock timeout had
  been set either via the experimental
  [PDB_GC_DAILY_PARTITION_DROP_LOCK_TIMEOUT_MS](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  variable, or other means, PuppetDB might fail to interpret the value
  correctly, and as a result, fail to prune older data correctly.
  [(PDB-5141)](https://tickets.puppetlabs.com/browse/PDB-5141)
* All reports can be queried by including type = "any" as a query filter.
  [PDB-4766](https://tickets.puppetlabs.com/browse/PDB-4766)
* The ssl-setup command (which is also invoked by the PuppetDB package
  installation scripts) should handle ssl-related filesystem permissions more
  carefully. Previously it might reset them when it shouldn't have, and/or
  leave them briefly with incorrect, potentially overly permissive values.
  [PDB-2590](https://tickets.puppetlabs.com/browse/PDB-2590)

## PuppetDB 7.4.0

The version was not released in order to include the security fix.

## PuppetDB 7.3.1

Released 26 April 2021. The 7.3.0 tag was burned in order to update additional
dependencies.

### New features and improvements

- Added a new query parameter `explain` that will return the query plan and
  actual run time statistics for the query.
  [PDB-5055](https://tickets.puppetlabs.com/browse/PDB-5055)

- Add ability to disable storage of resource events
  [PDB-3635](https://tickets.puppetlabs.com/browse/PDB-3635)

### Bug fixes

- Fixed a bug in the ssl-setup command that would insert a duplicate setting
  into the jetty.ini config.
  [PDB-5084](https://tickets.puppetlabs.com/browse/PDB-5084)
- PuppetDB will no longer return HTML formatted stack traces from the API
  endpoint, now only the error message will be returned. The full error can
  still be found it the logs if needed.
  [PDB-5063](https://tickets.puppetlabs.com/browse/PDB-5063)
- Trailing characters after a query, which were usually from mismatched `]` in
  an AST query, will no longer be ignored. Instead an error will be returned to
  alert the user to the potential error in their query.
  [PDB-2488](https://tickets.puppetlabs.com/browse/PDB-2488)

## PuppetDB 7.2.0

Released 24 February 2021

### New features and improvements

- Added two new users `connection-migrator-username` and `connection-username`
  in `database.ini` config file. The new users are used to establish connections
  to the database when the connection username is different from the database
  username (this is the case for managed PostgreSQL in Azure)
  [PDB-4934](https://tickets.puppetlabs.com/browse/PDB-4934)
- A new metric (:concurrent-depth), which counts the number of /cmd API requests
  that are waiting to write to the disk, was added.
  [PDB-4268](https://tickets.puppetlabs.com/browse/PDB-4268)
- A new metric (new-fact-time) was added under puppetlabs.puppetdb.storage. This
  metric measures the time it takes to persist facts for a never before seen
  certname.
  [PDB-3418](https://tickets.puppetlabs.com/browse/PDB-3418)
- The performance dashboard is now accessible on the HTTPS port. In PE, if the
  PuppetDB is using a certificate allowlist, users can authenticate their
  connection with an rbac token as a URL parameter.
  [PDB-3159](https://tickets.puppetlabs.com/browse/PDB-3159)

## PuppetDB 7.1.0

Released 9 February 2021

This release contains important security updates. See
([PDB-5000](https://tickets.puppetlabs.com/browse/PDB-5000)).

### New features and improvements

- (PE only) PuppetDB will synchronize with another instance more efficiently
  now. Previously it would synchronize each entity (factsets, reports, etc.)
  incrementally, holding open PostgreSQL queries/transactions throughout the
  entire process, which could take a long time. Those transactions could
  substantially harm database performance, increase table fragmentation, and if
  entangled with something like pglogical, increase transient storage
  requirements (by blocking WAL log reclaimaton). Now instead, the queries
  should completed up-front, as quickly as possible.
  [PDB-2420](https://tickets.puppetlabs.com/browse/PDB-2420)
- Changed the index on `certname` for report table partitions to be an index on
  `(certname, end_time)` to improve performance of certain queries from the
  PE console.
  [PDB-5003](https://tickets.puppetlabs.com/browse/PDB-5003)
- The `/metrics/v2` endpoint is now avaialble for external (non-localhost)
  connections, but requires authentication. This can be configured in a new
  configuration file `auth.conf`.
  [PDB-4811](https://tickets.puppetlabs.com/browse/PDB-4811)
- The `optimize_drop_unused_joins` query parameter can now optimize queries that
  contain a single count function.
  [PDB-4984](https://tickets.puppetlabs.com/browse/PDB-4984)
- Added a query bulldozer which is spawned during periodic GC when PuppetDB
  attempts to drop partitioned tables. The bulldozer will cancel any queries
  blocking the GC process from getting the AccessExclusiveLocks it needs in
  order to drop a partition. The [PDB_GC_QUERY_BULLDOZER_TIMEOUT_MS
  setting](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  allows users to disable the query-bulldozer if needed.
  [PDB-4948](https://tickets.puppetlabs.com/browse/PDB-4948)

### Bug fixes

- Previously an attempt to stop (or restart) PuppetDB might appear to succeed,
  even though some of its components were actually still running. That's because
  PuppetDB wasn't actually waiting for some of the internal tasks to finish as
  had been expected. Now PuppetDB should block during stop or restart until all
  of the components have actually shut down. This issue is a likely contributor
  to some cases where PuppetDB appeared to restart/reload successfully, but sync
  never started working again.
  [PDB-4974](https://tickets.puppetlabs.com/browse/PDB-4974)
- Various security fixes.
  ([PDB-5000](https://tickets.puppetlabs.com/browse/PDB-5000))


## PuppetDB 7.0.1

Released 15 December 2020

### Bug fixes

- Puppet Enterprise (PE) only: fixed an issue where PuppetDB wouldn't
  exit maintenance mode if garbage collection was disabled and sync was enabled.
  ([PDB-4975](https://tickets.puppetlabs.com/browse/PDB-4975))

## PuppetDB 7.0.0

Released 19 November 2020

### Bug fixes

- PuppetDB no longer retries queries internally, suppressing some transient
  connection errors. Instead, it immediately returns an error code.
  You can restore the previous behavior by setting the
  `PDB_USE_DEPRECATED_QUERY_STREAMING_METHOD` environment variable. See the
  [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details.

- PuppetDB won't hold an extra database connection open while generating query
  responses. Previously it would create and hold an extra connection open during
  the initial phase of the response. You can restore the previous behavior by
  setting the `PDB_USE_DEPRECATED_QUERY_STREAMING_METHOD` environment
  variable. See the [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details.

### Upgrading

- Running PuppetDB with PostgreSQL 9.6 or 10 is no longer supported. Use PostgreSQL 11 or greater instead.

