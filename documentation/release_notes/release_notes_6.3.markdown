---
title: "PuppetDB release notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

[configure_postgres]: ./configure.html#using-postgresql
[kahadb_corruption]: /puppetdb/4.2/trouble_kahadb_corruption.html
[pg_trgm]: http://www.postgresql.org/docs/current/static/pgtrgm.html
[upgrading]: ./api/query/v4/upgrading-from-v3.html
[puppetdb-module]: https://forge.puppetlabs.com/puppetlabs/puppetdb
[migrate]: /puppetdb/3.2/migrate.html
[upgrades]: ./upgrade.html
[metrics]: ./api/metrics/v1/changes-from-puppetdb-v3.html
[pqltutorial]: ./api/query/tutorial-pql.html
[stockpile]: https://github.com/puppetlabs/stockpile
[queue_support_guide]: ./pdb_support_guide.html#message-queue
[upgrade_policy]: ./versioning_policy.html#upgrades
[facts]: ./api/query/v4/facts.html

## PuppetDB 6.3.5

### New features and improvements

- **New `ignored` metric.** The `ignored` metric tracks the number of obsolete
  commands since the last restart. For more on the `ignored` metric, see[Metrics
  endpoint][metrics]. [PDB-4278](https://tickets.puppetlabs.com/browse/PDB-4278)

### Bug fixes

- Fixed an issue where PQL queries with dot notation required an extra
  space to terminate the dotted field. For example,
  `inventory[]{facts.os.family="Debian" }` would fail because PuppetDB parsed
  the `=` operator as part of the dotted field.
  [PDB-3284](https://tickets.puppetlabs.com/browse/PDB-3284)

- Fixed an issue where PuppetDB terminated a migration with a Postgres exception
  if `standard_conforming_strings` was not set to `true`. PuppetDB now verifies
  the setting before checking if any migrations are necessary. [PDB-4509](https://tickets.puppetlabs.com/browse/PDB-4509)  

- Fixed a bug that prevented command size metrics from being recorded and the
  `max-command-size` config option from working properly.
  [PDB-4502](https://tickets.puppetlabs.com/browse/PDB-4502)
  
- Fixed an issue where package upgrades on CentOS 6 would sometimes fail when upgrading from older versions of PuppetDB (for example, 5.2) to more recent versions (for example, 6.3+). [PDB-4373](https://tickets.puppetlabs.com/browse/PDB-4373)  

## PuppetDB 6.3.4

### Bug fixes

- Puppet DB database migrations failed due to a bug in the most recent releases of PostgreSQL 9.4.23, 9.5.18, 9.6.14, 10.9, and 11.4. This release does not change migration behavior, but includes changes to PuppetDB's database migration to avoid triggering the issue. See [PostgreSQL bug #15865](https://www.postgresql.org/message-id/15865-17940eacc8f8b081%40postgresql.org) for details about the issue. [PDB-4422](https://tickets.puppetlabs.com/browse/PDB-4422)

### Contributors

Austin Blatt, Heston Hoffman, Robert Roland, and Zachary Kent

## PuppetDB 6.3.3

### New features and improvements

- **Order `facts` and `fact-contents` by value.** The v4 `/facts` endpoint now supports ordering by fact value. For more information, see [Facts][facts]. [PUP-3687](https://tickets.puppetlabs.com/browse/PDB-3687)

### Bug fixes

- Certnames using unusual characters or that are very long will now be stored properly for catalogs. In previous releases, certnames with special characters, or very long certnames, caused duplicate node entries. [PUP-4390](https://tickets.puppetlabs.com/browse/PDB-4390)

- Resolved a bug  where garbage collection table names were causing a conflict. [PUP-4347](https://tickets.puppetlabs.com/browse/PDB-4347)

- In previous releases, if the database was inaccessible during startup, PuppetDB could become unkillable. [PUP-4308](https://tickets.puppetlabs.com/browse/PDB-4308)

- Resolved an bug that was causing PuppetDB to crash when performing older database migrations. [PUP-3840](https://tickets.puppetlabs.com/browse/PDB-3840)

- PuppetDB now logs the correct error when an attempt to query a remote PuppetDB server for sync fails. Previously, PuppetDB was incorrectly reporting a `FileNotFoundException`.
 [PUP-3592](https://tickets.puppetlabs.com/browse/PDB-3592)

### Contributors

Austin Blatt, Erik Hansen, Heston Hoffman, Jean Bond, Rob Browning,
Robert Roland, Wyatt Alt, and Zak Kent

## PuppetDB 6.3.1

### New features and improvements

- **Import and export** `configure expiration` **commands.** You can now use import and export to check which nodes have fact expiration disabled. For more information about the `configure expiration` command, see [Configure expiration wire format](./api/wire_format/configure_expiration_format_v1.markdown). [PDB-4275](https://tickets.puppetlabs.com/browse/PDB-4275)

- **View a node's lifetime data.** Use the `/nodes` endpoint with an `include_fact_expiration=true` argument to check if a node's facts are set to never expire. This is an experimental command and could be altered or removed in a future release. For more information, see [Nodes endpoint](./api/query/v4/nodes.markdown). [PDB-4271](https://tickets.puppetlabs.com/browse/PDB-4271)

### Bug fixes

- **Storing catalogs with Unicode Unicode alphanumeric tags.** PuppetDB now successfully stores catalogs with Unicode alphanumeric tags.  [PDB-4326](https://tickets.puppetlabs.com/browse/PDB-4326)

- **Resource event duplicates in reports.** Puppet agent was sending duplicate events for failing `exec` calls, causing PuppetDB report inserts to fail. This fix adds additional columns to the primary key calculation for events, and ignores duplicate rows at insert. [PDB-4315](https://tickets.puppetlabs.com/browse/PDB-4315)

### Contributors

Austin Blatt, Chris Roddy, Ethan J. Brown, Heston Hoffman, Jean Bond,
Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 6.3.0

### New features and improvements

- **New** `configure expiration` **command.** Use `configure expiration` to specify if a `factset` should or should not be a candidate for expiration. This is an experimental command and could be altered or removed in a future release. For more information, see [PuppetDB: Commands endpoint](./api/command/v1/commands.md). [PDB-4270](https://tickets.puppetlabs.com/browse/PDB-4270)

- **Primary key added to the** `resource_events` **table**. This allows the use of `pg_repack` to reclaim space without taking the table offline. This change rewrites the entire `resource_events` table, so make sure you have more than the existing table's size available during the upgrade. The upgrade time is relative to the size of your table.[PDB-3911](https://tickets.puppetlabs.com/browse/PDB-3911)

### Bug fixes

- **Logback integration.** The Logstash dependency was accidentally removed in a previous release. The dependency has been reinstated. [PDB-4277](https://tickets.puppetlabs.com/browse/PDB-4277)

- **Improved** `certname` **handling.** Previously, PuppetDB was unable to process commands that were submitted with a `certname` containing special characters such as `/`, `/`, `:` `_` or `0`, or exceeded 200 UTF-8 bytes if PuppetDB was restarted after the commands were submitted and before they were processed. PuppetDB now handles these commands correctly. [PDB-4257](https://tickets.puppetlabs.com/browse/PDB-4257)

- **Errors when using the** `in` **operator with** `"act_only":true`. Valid PQL queries, which use the `in` operator to compare against an array, that are being converted to AST via the `ast_only` option no longer throw a `NullPointerException`.  [PDB-4232](https://tickets.puppetlabs.com/browse/PDB-4232)

- **Errors when using the** `in` **operator with arrays**. PuppetDB would give an error if you used the `in` operator with an array of fact values or any array that did not have just one element. PuppetDB now accepts an array of fact values unless it finds an actual type mismatch. [PDB-4199](https://tickets.puppetlabs.com/browse/PDB-4199)

### Contributors

Austin Blatt, Cas Donoghue, Charlie Sharpsteen, Ethan J. Brown, Heston
Hoffman, Maggie Dreyer, Molly Waggett, Morgan Rhodes, Nate Wolfe, Rob
Browning, Robert Roland, and Zak Kent

## PuppetDB 6.2.0

### New features

- **Improved PostgreSQL support.** PuppetDB is now compatible with PostgreSQL version 10 and later. [PDB-3857](https://tickets.puppetlabs.com/browse/PDB-3857)

- **New Puppet Query Language (PQL) operators.** You can now use the negative operators `!=` and `!~`. [PDB-3471](https://tickets.puppetlabs.com/browse/PDB-3471)

### Bug fixes

- **PuppetDB no longer causes PostgreSQL to create large amounts of temporary files during garbage collection.** This issue caused PostgreSQL's log to flood if the `log_temp_files` option was set to a small enough value. [PDB-3924](https://tickets.puppetlabs.com/browse/PDB-3924)

### Security

- **Removed jackson-databind dependency.** We've blacklisted the jackson-databind dependency to resolve several security issues. [PDB-4236](https://tickets.puppetlabs.com/browse/PDB-4236)

### Contributors

Austin Blatt, David Lutterkort, Heston Hoffman, Morgan Rhodes, Nate
Wolfe, Rob Browning, Robert Roland, Wyatt Alt, and Zak Kent

## PuppetDB 6.1.0

### New features

- **PuppetDB's code is now compiled ahead-of-time**. This, along with a decrease in work at startup for simple commands, should notably decrease PuppetDB's startup time. For more on Ahead-of-time compilations, see [Ahead-of-time Compilation and Class Generation](https://clojure.org/reference/compilation).
[PDB-4108](https://tickets.puppetlabs.com/browse/PDB-4108)

### Bug fixes

- **(PE Only) PuppetDB no longer syncs reports that are older than the `report-ttl`, but have not yet been garbage collected.** PuppetDB would sync reports when it performed an initial garbage collection on startup, and then sync reports from its remote, which likely had not performed garbage collection as recently.
[PDB-4158](https://tickets.puppetlabs.com/browse/PDB-4158)
- **PuppetDB skips unnecessary work when ingesting commands.** PuppetDB wasn't pulling `producer-timestamp` out of the incoming query parameters for `submit` command requests. This caused PuppetDB to not have the necessary information to tombstone obsolete commands if multiple `store facts` or `store catalog` commands were submitted for the same `certname` while the earlier commands were still in the queue waiting to be processed.
[PDB-4177](https://tickets.puppetlabs.com/browse/PDB-4177)
- **Improved error handling for invalid or malformed timestamps.** If you passed an invalid or malformed timestamp in a PQL query, PuppetDB treated it as a `null`, giving back an unexpected query result.
[PDB-4015](https://tickets.puppetlabs.com/browse/PDB-4015)

### Contributors

Austin Blatt, Daniel Kessler, Ethan J. Brown, Heston Hoffman, Molly
Waggett, Morgan Rhodes, Rob Browning, Robert Roland, and Zak Kent
