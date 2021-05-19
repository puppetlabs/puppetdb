---
title: "PuppetDB: Release notes"
layout: default
canonical: "/puppetdb/latest/release_notes.markdown"
---
# Release notes

[configure_postgres]: configure.markdown#using-postgresql
[kahadb_corruption]: /puppetdb/4.2/trouble_kahadb_corruption.html
[pg_trgm]: http://www.postgresql.org/docs/current/static/pgtrgm.html
[upgrading]: api/query/v4/upgrading-from-v3.markdown
[puppetdb-module]: https://forge.puppetlabs.com/puppetlabs/puppetdb
[migrate]: /puppetdb/3.2/migrate.html
[upgrades]: upgrade.markdown
[metrics]: api/metrics/v1/changes-from-puppetdb-v3.markdown
[pqltutorial]: api/query/tutorial-pql.markdown
[stockpile]: https://github.com/puppetlabs/stockpile
[queue_support_guide]: pdb_support_guide.markdown#message-queue
[upgrade_policy]: versioning_policy.markdown#upgrades
[facts]: api/query/v4/facts.markdown
[puppet_apply]: ./connect_puppet_apply.markdown

---

## PuppetDB 6.16.1

Released 26 April 2021. The 6.16.0 tag was burned in order to update additional
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

## PuppetDB 6.15.0

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

## PuppetDB 6.14.0

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

- Puppet Enterprise (PE) only: fixed an issue where PuppetDB wouldn't
  exit maintenance mode if garbage collection was disabled and sync was enabled.
  ([PDB-4975](https://tickets.puppetlabs.com/browse/PDB-4975))
- Previously an attempt to stop (or restart) PuppetDB might appear to succeed,
  even though some of its components were actually still running. That's because
  PuppetDB wasn't actually waiting for some of the internal tasks to finish as
  had been expected. Now PuppetDB should block during stop or restart until all
  of the components have actually shut down. This issue is a likely contributor
  to some cases where PuppetDB appeared to restart/reload successfully, but sync
  never started working again.
  [PDB-4974](https://tickets.puppetlabs.com/browse/PDB-4974)
- PuppetDB no longer retries queries internally, suppressing some transient
  connection errors. Instead, it immediately returns an error code.
  You can restore the previous behavior by setting the
  `PDB_USE_DEPRECATED_QUERY_STREAMING_METHOD` environment variable. See the
  [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details. [PDB-4962](https://tickets.puppetlabs.com/browse/PDB-4962)
- PuppetDB won't hold an extra database connection open while generating query
  responses. Previously it would create and hold an extra connection open during
  the initial phase of the response. You can restore the previous behavior by
  setting the `PDB_USE_DEPRECATED_QUERY_STREAMING_METHOD` environment
  variable. See the [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details.
- Various security fixes.
  ([PDB-5000](https://tickets.puppetlabs.com/browse/PDB-5000))

## PuppetDB 6.13.1

Released 27 October 2020

### Bug fixes

- Puppet Enterprise (PE) only: serialized the initial garbage collection and the
  initial sync to prevent a deadlock.
  ([PDB-4938](https://tickets.puppetlabs.com/browse/PDB-4938))

## PuppetDB 6.13.0

Released 20 October 2020

### New features and improvements

- PuppetDB can now log the AST and SQL corresponding to each incoming
  query when requested by the
  [log-queries](https://puppet.com/docs/puppetdb/latest/configure.html#log-queries)
  configuration option.
  ([PDB-4834](https://tickets.puppetlabs.com/browse/PDB-4834))

- PuppetDB now only drops the oldest report or events partition during
  the normal, periodic garbage collection when there's more than one
  candidate.  This decreases the length of time PuppetDB blocks
  operations since the drop attempts to acquire an exclusive lock on
  the entire table (i.e. reports, not just the partition), and so will
  block all subsequent access to that table until it finishes.
  ([PDB-4901](https://tickets.puppetlabs.com/browse/PDB-4901))

- PuppetDB now unifies report and resource event clean up during a
  full garbage collection, instead of handling each in a separate
  transaction.  This ensures it only waits on the exclusive lock to
  drop relevant event partitions once.
  ([PDB-4902](https://tickets.puppetlabs.com/browse/PDB-4902))

- The fact path garbage collection process will now time out after 5
  minutes by default if it cannot acquire the locks it requires.  See
  the [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details.
  ([PDB-4907](https://tickets.puppetlabs.com/browse/PDB-4907))

- SQL commands issued during an attempt to process a command (store a
  report, update a factset, etc.) will now time out after 10 minutes
  by default, causing the command to be retried or discarded.  See the
  [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details.
  ([PDB-4906](https://tickets.puppetlabs.com/browse/PDB-4906))

- PuppetDB can now detect when the inputs in a catalog have not
  changed with respect to the previous catalog, and avoid storing them
  again.  ([PDB-4895](https://tickets.puppetlabs.com/browse/PDB-4895))

- Some additional indexing has been added to the catalog inputs
  storage which should improve query performance.
  ([PDB-4881](https://tickets.puppetlabs.com/browse/PDB-4881))

- The `certificate-whitelist` and `facts-blacklist` configuration
  options have been deprecated in favor of `certificate-allowlist` and
  `facts-blocklist`.  See also:
  https://puppet.com/blog/removing-harmful-terminology-from-our-products
  ([PDB-4872](https://tickets.puppetlabs.com/browse/PDB-4872))

- Puppet Enterprise (PE) only: by default, the sync
  `entity-time-limit` is now additionally enforced by interruption of
  the thread performing sync.  See the
  [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details.
  ([PDB-4909](https://tickets.puppetlabs.com/browse/PDB-4909))

### Bug fixes

- The report and resource event garbage collections will now time out
  if they have to wait longer than 5 minutes (by default) to acquire
  the required table lock.  This prevents them from blocking other
  related queries indefinitely, and prevents them from participating
  an any permanent deadlocks.  See the
  [configuration information](https://puppet.com/docs/puppetdb/latest/configure.html#experimental-environment-variables)
  for further details.
  ([PDB-4903](https://tickets.puppetlabs.com/browse/PDB-4903))

- Puppet Enterprise (PE) only: PuppetDB sync now defers to the report
  and resource event garbage collections in order to avoid blocking
  their access to the locks they require.
  ([PDB-4908](https://tickets.puppetlabs.com/browse/PDB-4908))

### Contributors

Austin Blatt, Rob Browning, and Zak Kent

## PuppetDB 6.12.0

Released 25 August 2020

### New features
  - **Adds support for Ubuntu 20.04 LTS**

### Bug fixes
  - **Fixes an issue with the catalog duplication percent metric.** This resulted in an error that prevented the dashboard from loading. [PDB-4855](https://tickets.puppetlabs.com/browse/PDB-4855)

  - **PQL queries now support the full range of 64-bit integers.** Queries were previously limited to the range of 32-bit integers. [PDB-4269](https://tickets.puppetlabs.com/browse/PDB-4269)

### Contributors

Austin Blatt, Maggie Dreyer, Rob Browning, and Zak Kent

## PuppetDB 6.11.3

Released 4 August 2020

### Bug fixes
  - Adds a missing index to all report partitions, improving report query performance. [PDB-4832](https://tickets.puppetlabs.com/browse/PDB-4832)

### Contributors

Austin Blatt, Rob Browning, and Zak Kent

## PuppetDB 6.11.2

Released 14 July 2020

### Bug fixes
  - Fixes a bug that caused PuppetDB to use a CTE that materialized a large table, slowing queries. [PDB-4769](https://tickets.puppetlabs.com/browse/PDB-4769)

### Security fixes
  - Our dependency on org.postgresql/postgresql was upgraded to 42.2.14 to fix CVE-2020-13692. [SEC-155](https://tickets.puppetlabs.com/browse/SEC-155)
    Note: PuppetDB does not store XML data types in PostgreSQL and should not be affected by this CVE.

### Contributors

Austin Blatt, Rob Browning, and Zak Kent

## PuppetDB 6.11.1

This version is included in PE version 2019.8, but is not available as an open source offering. It includes a minor bug fix.

## PuppetDB 6.11.0

### Upgrading

We recommend upgrading to PostgreSQL 11 or greater before upgrading to
PuppetDB 6.11.0. PostgreSQL 11 includes performance improvements which make
adding a non-null column with a default value much faster and should
significantly speed up the migration included in this release.

### New features
 - **Support for the storage of reports generated by Bolt Plans.**

 - **New report field `type` to specify the type of report submitted.** Use `agent` for a Puppet agent run, or `plan` for a report of a plan's apply block.

### Deprecations
   - Java 8, 9, and 10 have been deprecated. If these versions are used, PuppetDB will log a warning on startup. We recommend using Java 11 going forward.
   - Running PuppetDB with PostgreSQL 9.6 and 10 has been deprecated. Use PostgreSQL 11 instead.

### Contributors

Austin Blatt, Ethan J. Brown, Rob Browning, and Zak Kent

## PuppetDB 6.10.1

### Bug fixes

  - Fixed an issue that caused PuppetDB to fail to start on a database that had been in service before PuppetDB 4.0.0. [PDB-4709](https://tickets.puppetlabs.com/browse/PDB-4709)

## PuppetDB 6.10.0

### Upgrading

This upgrade contains a long running migration to the reports table,
which is typically the largest table in PuppetDB. Before upgrading to,
or past, this version of PuppetDB, you are strongly encouraged to consider
deleting your reports table. This will drastically shorten your upgrade time
and get you back online much faster. If you are on a `5.2.z` version, please
upgrade to `5.2.14` or later and then take advantage of the `delete-reports`
subcommand. Otherwise, consult the documentation on how to [truncate the
reports table manually](https://puppet.com/docs/puppetdb/latest/upgrade.html#truncate-your-reports-table).

### New features
 - **New `delete-reports` subcommand of the `puppetdb` command.** The command stops the PuppetDB service and deletes all reports from the database. [PDB-2398](https://tickets.puppetlabs.com/browse/PDB-2398)

 - **New `migrate` configuration option in database settings.** On startup, PuppetDB will only perform migrations if the value is `true`. If the value is `false` and a migration is necessary, PuppetDB will exit with an error. [PDB-3751](https://tickets.puppetlabs.com/browse/PDB-3751)

 - **New `migrator-username` option in database settings.** You can now configure PuppetDB to attempt to prevent concurrent migrations or any access to a database that's in an unexpected format, either too new or too old. See [Configuring PuppetDB](https://puppet.com/docs/puppetdb/latest/configure.html#coordinating-database-migrations) for further information. [PDB-4636](https://tickets.puppetlabs.com/browse/PDB-4636) [PDB-4637](https://tickets.puppetlabs.com/browse/PDB-4637) [PDB-4639](https://tickets.puppetlabs.com/browse/PDB-4639)
### Bug fixes

  - Fixed an issue that would cause PE's sync to fail and never retry when one PuppetDB had been upgraded and the other had not. PE's sync will now fail and retry. [PDB-4682](https://tickets.puppetlabs.com/browse/PDB-4682)

### Contributors

Austin Blatt, Ethan J. Brown, Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 6.9.1

### New features 
 - **New `initial-report-threshold` configuration option in sync settings.** On startup, PuppetDB will only sync reports newer than the threshold. Older reports will still be transferred on subsequent periodic syncs. [PDB-3751](https://tickets.puppetlabs.com/browse/PDB-3751)
 
### Resolved issue 
- To prevent information exposure as a result of [CVE-2020-7943](https://puppet.com/security/cve/CVE-2020-7943), the `/metrics/v1` endpoints are disabled by default, and access to the `/metrics/v2` endpoints are restricted to localhost.

### Contributors

Austin Blatt, Claire Cadman, and Morgan Rhodes

## PuppetDB 6.9.0

### New features and improvements

- **File indexing on `catalog_resources`.** After you configure the PostgreSQL `pg_trgm` extension, PuppetDB adds an index to the file column on the `catalog_resources` table. [PDB-4640](https://tickets.puppetlabs.com/browse/PDB-4640)
  > **Note:** As of this release, running PostgreSQL without the `pg_trgm` extension is deprecated. 

- **Improved queries.** PuppetDB now has an [experimental query optimizer](./api/query/v4/query.markdown#experimental_query_optimization) that may be able to substantially decrease the cost and response time of some queries. [PDB-4512](https://tickets.puppetlabs.com/browse/PDB-4512)

### Bug fixes

- Fixed an issue affecting PE installations where PuppetDB would fail to purge a deactivated node. [PDB-4479](https://tickets.puppetlabs.com/browse/PDB-4479) 

- Database migrations could fail if there were long periods of inactivity in the `resource_events`, table and a client's server wasn't using UTC.[PDB-4641](https://tickets.puppetlabs.com/browse/PDB-4641)

### Contributors

Austin Blatt, Heston Hoffman, Morgan Rhodes, Rob Browning, Robert Roland, and Zak Kent


## PuppetDB 6.8.1

### Bug fixes

- Database migrations would fail for timezones with positive UTC offsets. [PDB-4626](https://tickets.puppetlabs.com/browse/PDB-4626)

### Contributors

Austin Blatt, Heston Hoffman, Reinhard Vicinus, Robert Roland, and Zak Kent

## PuppetDB 6.8.0

### New features and improvements

- **New `resource-events-ttl` configuration parameter.** Use the
  `resource-events-ttl` configuration parameter to automatically delete report events older
  than the specified time. The parameter rounds up to the nearest day.
  For example, `14h` rounds up to `1d`. For more information, see [Configuring
  PuppetDB](configure.markdown#resource-events-ttl).
  [PDB-2487](https://tickets.puppetlabs.com/browse/PDB-2487)

- **New `delete` command.** Use the `delete` command to immediately delete the
  data associated with a certname. For more information, see
  [Commands endpoint](api/admin/v1/cmd.markdown#delete-version-1).
  [PDB-3300](https://tickets.puppetlabs.com/browse/PDB-3300)

### Bug fixes

- Resolved an issue where an unreachable
  PostgreSQL server could cause PuppetDB to exhaust its connection pool,
  requiring a restart.
  [PDB-4579](https://tickets.puppetlabs.com/browse/PDB-4579)

### Contributors

Austin Blatt, Ethan J. Brown, Manuel Laug, Molly Waggett, Morgan
Rhodes, Nick Walker, Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 6.7.3

### Bug fixes

- This release includes various security improvements.

### Contributors

Austin Blatt, Ethan J. Brown, Heston Hoffman, Markus Opolka, Morgan
Rhodes, and Nate Wolfe

## PuppetDB 6.7.2

### Bug fixes

- Fixed an issue that caused PuppetDB to shut down if the initial Postgres
  connection failed. PuppetDB now retries the connection if it fails.

### Contributors

Austin Blatt, Ethan J. Brown, Heston Hoffman, Morgan Rhodes, Nate
Wolfe, Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 6.7.1

### Bug fixes

- Fixed an issue where PuppetDB terminated a migration with a Postgres exception
  if `standard_conforming_strings` was not set to `true`. PuppetDB now verifies
  the setting before checking if any migrations are necessary. [PDB-4509](https://tickets.puppetlabs.com/browse/PDB-4509)  

- Fixed a bug that prevented command size metrics from being recorded and the
  `max-command-size` config option from working properly.
  [PDB-4502](https://tickets.puppetlabs.com/browse/PDB-4502)
  
- This release restores the cipher suites required to connect to Puppet Server using
  TLS versions TLSv1.0 and TLSv1.1. [PDB-4513](https://tickets.puppetlabs.com/browse/PDB-4513)

### Contributors

Austin Blatt, Eric Griswold, Ethan J. Brown, Heston Hoffman, Molly
Waggett, Robert Roland, Scot Kreienkamp, Vadym Chepkov, and Zak Kent

## PuppetDB 6.7.0

### New features and improvements

- **Debian 10 support** - PuppetDB packages are now available for Debian 10. These packages require Java 11 to be installed, rather than Java 8. [PDB-4469](https://tickets.puppetlabs.com/browse/PDB-4469)  

- **New `ignored` metric.** The `ignored` metric tracks the number of obsolete
  commands since the last restart. For more on the `ignored` metric, see
  [Metrics endpoint][metrics]. [PDB-4278](https://tickets.puppetlabs.com/browse/PDB-4278)

- **Return a specific fact or resource paramater with `inventory` and `resources` endpoints.** You can now use dot notation
  with `inventory` and `resources` endpoints to return a specific fact or resource parameter instead of the
  entire JSON file [PDB-2634](https://tickets.puppetlabs.com/browse/PDB-2634). 
  
  For examples of using dot notation in PQL and AST, see the following: 
  - [Puppet Query Language (PQL) examples](api/query/examples-pql.markdown) 
  - [AST query language (AST)](api/query/v4/ast.markdown)

### Bug fixes

- Fixed an issue where PQL queries with dot notation required an extra
  space to terminate the dotted field. For example, `inventory[]{
  facts.os.family="Debian" }` would fail because PuppetDB parsed the `=` operator as part of the dotted field. [PDB-3284](https://tickets.puppetlabs.com/browse/PDB-3284)

## PuppetDB 6.6.0

### Bug fixes

- A change in the `puppetdb-termini` package for 6.5.0 broke SSL connections that
  did not use Puppet's CA. This fix adds [the `verify_client_certificate` configuration option][puppet_apply].
  By default, `verify_client_certificate` only allows SSL connections authenticated by the
  Puppet CA. When set to `false`, it allows the use of other SSL connections. [PDB-4487](https://tickets.puppetlabs.com/browse/PDB-4487)

- Fixed an issue where package upgrades on CentOS 6 would sometimes fail when
  upgrading from older versions of PuppetDB (for example, 5.2) to more recent versions
  (for example, 6.3+). [PDB-4373](https://tickets.puppetlabs.com/browse/PDB-4373)

### Contributors

Austin Blatt, Craig Watson, Ethan J. Brown, Heston Hoffman, Nate Wolfe,
Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 6.5.0

### New features and improvements

- **New experimental `catalog-input-contents` endpoint.** Use the [`catalog-input-contents`](./api/query/v4/catalog_input_contents.markdown) endpoint to query for the most recent
catalog inputs that PuppetDB has received for any nodes. ([PDB-4371](https://tickets.puppetlabs.com/browse/PDB-4371)

- **Submit `inputs` to a catalog.** PuppetDB can now optionally store "inputs", such as Hiera keys, during catalog compilation. See the [command's wire format](api/wire_format/catalog_inputs_format_v1.markdown) for more information on how to submit them. [PDB-4372](https://tickets.puppetlabs.com/browse/PDB-4372)

### Bug fixes

 - We've updated the default auto-vacuum settings for several tables which PuppetDB was vacuuming more frequently than neccessary. These changes will apply once at the next upgrade. [PDB-3745](https://tickets.puppetlabs.com/browse/PDB-3745)

### Contributors

Austin Blatt, Ethan J. Brown, Heston Hoffman, Josh Partlow, Nate Wolfe,
Nick Walker, Patrick Carlisle, Rob Browning, and Robert Roland

## PuppetDB 6.4.0

### Bug fixes

- This bug affects Puppet Enterprise (PE) only. After a restart or downtime, PuppetDB did not sync its package inventory, resulting in PuppetDB nodes with desynced fact hashes. [PDB-4266](https://tickets.puppetlabs.com/browse/PDB-4266)

### Contributors

Austin Blatt, Ethan J. Brown, Jean Bond, Markus Opolka, Morgan Rhodes,
Nate Wolfe, Rob Browning, Robert Roland, and Zak Kent
