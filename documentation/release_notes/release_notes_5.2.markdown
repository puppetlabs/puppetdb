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

---

## PuppetDB 5.2.13

### New features
 - **New `initial-report-threshold` configuration option in sync settings.** On startup, PuppetDB will only sync reports newer than the threshold. Older reports will still be transferred on subsequent periodic syncs. [PDB-3751](https://tickets.puppetlabs.com/browse/PDB-3751)

### Contributors

Austin Blatt, Claire Cadman, and Zak Kent

## PuppetDB 5.2.12

### New features and improvements

- **New `delete` command.** Use the `delete` command to immediately delete the
  data associated with a certname. For more information, see [Commands
  endpoint](./api/admin/v1/cmd.html#delete-version-1). [PDB-3300](https://tickets.puppetlabs.com/browse/PDB-3300)

### Bug fixes

- Resolved an issue where an unreachable
  PostgreSQL server could cause PuppetDB to exhaust its connection pool,
  requiring a restart.
  [PDB-4579](https://tickets.puppetlabs.com/browse/PDB-4579)

- This release includes various security improvements.

### Contributors

Austin Blatt, Heston Hoffman, Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 5.2.11

### Bug fixes

- Fixed an issue that caused PuppetDB to shut down if the initial Postgres
  connection failed. PuppetDB now retries the connection if it fails.

### Contributors

Austin Blatt, Ethan J. Brown, Heston Hoffman, Rob Browning, Robert
Roland, and Zak Kent

## PuppetDB 5.2.10

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
  `max-command-size` config option from working properly. [PDB-4502](https://tickets.puppetlabs.com/browse/PDB-4502)

### Contributors

Austin Blatt, Eric Griswold, Heston Hoffman, Molly Waggett, Rob
Browning, Robert Roland, Scot Kreienkamp, and Zak Kent

## PuppetDB 5.2.9

### New features and improvements

- **Order `facts` and `fact-contents` by value.** The v4 `/facts` endpoint now supports ordering by fact value. For more information, see [Facts][facts]. [PUP-3687](https://tickets.puppetlabs.com/browse/PDB-3687)

### Bug fixes

- PuppetDB database migrations failed due to a bug in the most recent releases of PostgreSQL 9.4.23, 9.5.18, 9.6.14, 10.9, and 11.4. This release does not change migration behavior, but includes changes to PuppetDB's database migration to avoid triggering the issue. See [PostgreSQL bug #15865](https://www.postgresql.org/message-id/15865-17940eacc8f8b081%40postgresql.org) for details about the issue. [PDB-4422](https://tickets.puppetlabs.com/browse/PDB-4422)

- Certnames using unusual characters or that are very long will now be stored properly for catalogs. In previous releases, certnames with special characters, or very long certnames, caused duplicate node entries. [PUP-4390](https://tickets.puppetlabs.com/browse/PDB-4390)

- Resolved a bug  where garbage collection table names were causing a conflict. [PUP-4347](https://tickets.puppetlabs.com/browse/PDB-4347)

- Resolved an bug that was causing PuppetDB to crash when performing older database migrations. [PUP-3840](https://tickets.puppetlabs.com/browse/PDB-3840)

- In previous releases, if the database was inaccessible during startup, PuppetDB could become unkillable. [PUP-4308](https://tickets.puppetlabs.com/browse/PDB-4308)

- PuppetDB now logs the correct error when an attempt to query a remote PuppetDB server for sync fails. Previously, PuppetDB was incorrectly reporting a `FileNotFoundException`.
 [PUP-3592](https://tickets.puppetlabs.com/browse/PDB-3592)

- **Storing catalogs with Unicode Unicode alphanumeric tags.** PuppetDB now successfully stores catalogs with Unicode alphanumeric tags.  [PDB-4326](https://tickets.puppetlabs.com/browse/PDB-4326)

### Contributors

Austin Blatt, Chris Roddy, Erik Hansen, Heston Hoffman, Jean Bond, Rob
Browning, Robert Roland, Wyatt Alt, and Zak Kent

## PuppetDB 5.2.8

### New features

- **New Puppet Query Language (PQL) operators.** You can now use the negative operators `!=` and `!~`. [PDB-3471](https://tickets.puppetlabs.com/browse/PDB-3471)

### Bug fixes

- **Logback integration.** The Logstash dependency was accidentally removed in a previous release. The dependency has been reinstated. [PDB-4277](https://tickets.puppetlabs.com/browse/PDB-4277)

- **Improved** `certname` **handling.** Previously, PuppetDB was unable to process commands that were submitted with a `certname` containing special characters such as `/`, `/`, `:` `_` or `0`, or exceeded 200 UTF-8 bytes if PuppetDB was restarted after the commands were submitted and before they were processed. PuppetDB now handles these commands correctly. [PDB-4257](https://tickets.puppetlabs.com/browse/PDB-4257)

- **Errors when using the** `in` **operator with** `"act_only":true`. Valid PQL queries, which use the `in` operator to compare against an array, that are being converted to AST via the `ast_only` option no longer throw a `NullPointerException`.  [PDB-4232](https://tickets.puppetlabs.com/browse/PDB-4232)

- **Errors when using the** `in` **operator with arrays**. PuppetDB would give an error if you used the `in` operator with an array of fact values or any array that did not have just one element. PuppetDB now accepts an array of fact values unless it finds an actual type mismatch. [PDB-4199](https://tickets.puppetlabs.com/browse/PDB-4199)

### Contributors

Austin Blatt, Cas Donoghue, Charlie Sharpsteen, Ethan J. Brown, Heston
Hoffman, Molly Waggett, Nate Wolfe, Rob Browning, Robert Roland, Wyatt
Alt, and Zak Kent

## 5.2.7

### Improvements

- **Improved PostgreSQL support.** PuppetDB is now compatible with PostgreSQL version 10 and later. [PDB-3857](https://tickets.puppetlabs.com/browse/PDB-3857)

### Bug Fixes

- **(PE Only) PuppetDB no longer syncs reports that are older than the `report-ttl`, but have not yet been garbage collected.** PuppetDB would sync reports when it performed an initial garbage collection on startup, and then sync reports from its remote, which likely had not performed garbage collection as recently.
[PDB-4158](https://tickets.puppetlabs.com/browse/PDB-4158)
- **PuppetDB skips unnecessary work when ingesting commands.** PuppetDB wasn't pulling `producer-timestamp` out of the incoming query parameters for `submit` command requests. This caused PuppetDB to not have the necessary information to tombstone obsolete commands if multiple `store facts` or `store catalog` commands were submitted for the same `certname` while the earlier commands were still in the queue waiting to be processed.
[PDB-4177](https://tickets.puppetlabs.com/browse/PDB-4177)
- **Improved error handling for invalid or malformed timestamps.** If you passed an invalid or malformed timestamp in a PQL query, PuppetDB treated it as a `null`, giving back an unexpected query result.
[PDB-4015](https://tickets.puppetlabs.com/browse/PDB-4015)
- **PuppetDB no longer causes PostgreSQL to create large amounts of temporary files during garbage collection.** This issue caused PostgreSQL's log to flood if the `log_temp_files` option was set to a small enough value. [PDB-3924](https://tickets.puppetlabs.com/browse/PDB-3924)

### Security

- We've blacklisted the jackson-databind dependency to resolve several security issues. [PDB-4236](https://tickets.puppetlabs.com/browse/PDB-4236)

### Contributors

Austin Blatt, Brandon High, David Lutterkort, Ethan J. Brown, Heston
Hoffman, Molly Waggett, Morgan Rhodes, Nate Wolfe, Rob
Browning, Robert Roland, and Zak Kent

## 5.2.6

PuppetDB 5.2.6 is a security, new feature, and bug-fix release.

### Security

- Our dependency on trapperkeeper-webserver-jetty9 was upgraded to 2.2.0 to fix CVE-2017-7658. [PDB-4160](https://tickets.puppetlabs.com/browse/PDB-4160)
- Our dependency on jackson-databind was upgraded to 2.9.7 to fix CVE-2018-7489. [RE-11706](https://tickets.puppetlabs.com/browse/RE-11706)


### New features

- Added support for fact blacklist regexes. Omits facts whose name completely matched any of the expressions provided. Added a "facts-blacklist-type" database configuration option which defaults to "literal", producing the existing behavior, but can be set to "regex" to indicate that the facts-blacklist items are java patterns. [PDB-3928](https://tickets.puppetlabs.com/browse/PDB-3928) 
- Currently, when building and testing from source, internal Puppet repositories are consulted by default, which may cause the process to take longer than necessary. Until the defaults are changed, the delays can be avoided by setting `PUPPET_SUPPRESS_INTERNAL_LEIN_REPOS=true` in the environment. [PDB-4135](https://tickets.puppetlabs.com/browse/PDB-4135)
- An `upgrade` subcommand has been added that should be useful for cases where you want to upgrade across multiple major versions without skipping major versions (as per the versioning). [PDB-3993](https://tickets.puppetlabs.com/browse/PDB-3993)

### Bug fixes

- Previously http submission with `command_broadcast` enabled returned the last response. As a result, a failure would be shown if the last connection produced a 503 response, even though there was a successful PuppetDB response and the minimum successful responses had been met. This issue does not occur with responses that raised an exception. Because the Puppet `http_pool` does not raise 503 as an exception, this issue can be seen when the PuppetDB is in maintenance mode. This is now fixed and changes the behavior to send the last successful response when the minimum successful submissions have been met. [PDB-4020](https://tickets.puppetlabs.com/browse/PDB-4020)
- The find/fix database connection errors after clj-parent 2.0 dep upgrades has now been fixed. [PDB-3952](https://tickets.puppetlabs.com/browse/PDB-3952)

### Contributors
Austin Blatt, Britt Gresham, Claire Cadman, Ethan J. Brown, Garrett Guillotte, Jarret Lavallee, Molly Waggett, Morgan Rhodes, Rob Browning, Robert Roland, and Zak Kent

## 5.2.5

NOTE: This version was not officially released, as additional fixes
came in between the time we tagged this and the time we were going to
publish release artifacts.

## 5.2.4

PuppetDB 5.2.4 is a minor bug-fix release.

-   [All issues resolved in PuppetDB 5.2.4](https://tickets.puppetlabs.com/issues/?jql=fixVersion%20%3D%20%27PDB%205.2.4%27)

### Bug fixes

-   In previous versions of PuppetDB (starting with 5.2.0), the `~` operator would match
    the JSON-encoded representation of a fact value rather than the value itself. For
    example, this query would unexpectedly fail to match a fact that started with `file`:

    ```
    ~ ^file
    ```

    However, this query would unexpectedly match the fact:

    ```
    ~ ^"file
    ```

    PuppetDB 5.2.4 resolves this issue by correctly matching regular expresions against
    fact values. ([PDB-3930](https://tickets.puppetlabs.com/browse/PDB-3930))

### Contributors

Garrett Guillotte, Morgan Rhodes, Rob Browning, and Zak Kent

## 5.2.3

NOTE: This version was not officially released, as additional fixes
came in between the time we tagged this and the time we were going to
publish release artifacts.

## 5.2.2

PuppetDB 5.2.2 is a bug-fix release.

### Bug fixes

-   In previous versions of PuppetDB, passing the `node_state` filter criteria as part of a conjuction operator (`and` and `or`) didn't work. PuppetDB 5.2.2 resolves this issue. ([PDB-3808](https://tickets.puppetlabs.com/browse/PDB-3808))

-   PuppetDB 5.2.2 fixes a regression in previous versions of PuppetDB 5.2.x that caused path queries against structured facts to fail when the path included a regular expression component or an array index. ([PDB-3852](https://tickets.puppetlabs.com/browse/PDB-3852))

-   When the Puppet Enterprise (PE) package inspector is configured, duplicate package data can sometimes be submitted from the `gem` and similar package providers. PuppetDB 5.2.2 removes these duplicates, instead of rejecting the data as in previous versions of PuppetDB. ([PDB-3862](https://tickets.puppetlabs.com/browse/PDB-3862))

### Contributors
Andrew Roetker, Garrett Guillotte, and Zak Kent

## 5.2.1

PuppetDB 5.2.1 is a bug-fix release that improves facts query performance and improves command queue management.

### Bug fixes

-   PuppetDB 5.2.1 significantly improves the performance of facts queries that constrain 'name' to a single value, such as `facts [value, count()] { name = 'osFamily' group by value }`. ([PDB-3838](https://tickets.puppetlabs.com/browse/PDB-3838))

-   If you submitted a malformed message to the command queue in previous versions of PuppetDB, the command queue would grow upon receiving the message but would not reduce in size when the message was discarded. PuppetDB 5.2.1 correctly reduces the command queue size when a malformed message is submitted to the queue. ([PDB-3830](https://tickets.puppetlabs.com/browse/PDB-3830))

### Contributors
Garrett Guillotte, Morgan Rhodes, Nick Walker, Rob Browning, Russell
Mull, and Zak Kent

## 5.2.0

PuppetDB 5.2.0 is a performance and feature release. Performance improvements are focused primarily on improvements to the storage model for facts.

### Upgrading

Upgrading is expected to be straightforward, but in light of the recent changes to facts storage users might expect some quirks:

-   The initial fact submission for each node post-upgrade triggers a full rewrite of fact data for that node, meaning facts processing might slow down for a short while. This could cause the queue size to increase temporarily for users operating near capacity. This should fix itself after Puppet's runinterval (30 minutes by default) has elapsed.

### Bug fixes

-   In previous versions of PuppetDB, resource types were passed to `Resource#new` in string form. This was problematic because it could result in the load of a Ruby resource type which then is in conflict with genereated Pcore resource type.

    PuppetDB 5.2.0 ensures that the indirection uses the same logic as Puppet when creating new resources.

### New features

-   PuppetDB 5.2.0 replaces the existing facts storage schema with a new one based on JSONB. This change doesn't result in any breaking API changes.

    This improvement provides multiple benefits and some incidental new features:

    -   Greatly improved garbage collection performance when large structured facts are updated.
    -   Improved performance of conjunction queries, such as where users request a list of hosts with facts meeting multiple criteria.
    -   Makes future development on facts querying more amenable to the type of descendence queries currently possible with the inventory endpoint.

-   This version of PuppetDB prevents the query engine from limiting queries against the v4 endpoint to active nodes, in cases where the relevant entity has no certname field. This facilitates PQL queries against fact_paths and environments.

-   When using the benchmark tool in PuppetDB 5.2.0, every host is enqueued once before starting the simulation loop.

-   PuppetDB 5.2.0 tracks how long a migration takes and logs them to improve testing and support awareness.

-   This version of PuppetDB adds support for equality conditions on arrays in PQL.

### Improvements

-   If PuppetDB is out of sync, the two most important datasets to reconcile are catalogs and facts, because catalogs are used for exported resource queries and facts are used to select groups of nodes for orchestration operations.

    However, previous versions of PuppetDB started a sync by transferring reports, which can be a significantly large dataset if two instances are out of sync by several days. This would delay reconciliation of catalogs and facts.

    PuppetDB 5.2.0 reorders the sync configuration so that entities are synced in the following order:

    -   catalogs
    -   facts
    -   reports
    -   nodes

    Catalogs and reports have switched priorities, and catalogs are now the highest priority.

-   PuppetDB 5.2.0 improves a query used to target packages for deletion.

-   This version of PuppetDB adds optimization for event queries with the `latest_report?` parameter.

-   PuppetDB 5.2.0 adds the latest report's `job_id` to the nodes endpoint. The `job_id` isn't present if the run wasn't part of a job.

### Contributors
Karel Březina, Katie Lawhorn, Mike Eller, Rob Browning, Russell Mull,
Scott Walker, Thomas Hallgren, Wyatt Alt, and Zak Kent

## 5.1.6

PuppetDB 5.1.6 is a PE-only release.

### Bug fixes

- The jackson-databind dependency has been upgraded to 2.9.8 to fix
  CVE-2018-5968, CVE-2018-19360, CVE-2018-19361, and CVE-2018-19362.
  ([PDB-4364](https://tickets.puppetlabs.com/browse/PDB-4364))

- When an invalid / malformed timestamp was passed in a PQL query, it
  was treated as a null, giving back an unexpected query result. The
  timestamp is now validated, and an error is returned to the user.
  ([PDB-4015](https://tickets.puppetlabs.com/browse/PDB-4015))

- When there are processing errors during an initial HA sync PuppetDB
  should now defer commands to the local queue as originally intended,
  (PE Only). ([PDB-4117](https://tickets.puppetlabs.com/browse/PDB-4117))

- A problem that could cause harmless, but noisy database connection
  errors during shutdown has been fixed.
  ([PDB-3952](https://tickets.puppetlabs.com/browse/PDB-3952))

### Contributors

Austin Blatt, Britt Gresham, Claire Cadman, David Lutterkort, Ethan J.
Brown, Garrett Guillotte, Heston Hoffman, Jarret Lavallee,
Molly Waggett, Morgan Rhodes, Nate Wolfe, Rob Browning, Robert Roland,
and Zak Kent

## 5.1.5

PuppetDB 5.1.5 is a bug-fix release.

### Bug fixes

-   Passing the `node_state` filter criteria as part of a conjuction operator (`and` and `or`) didn't work in previous versions of PuppetDB. PuppetDB 5.1.5 resolves this issue. ([PDB-3808](https://tickets.puppetlabs.com/browse/PDB-3808))

-   If you submitted a malformed message to the command queue in previous versions of PuppetDB, the command queue would grow upon receiving the message but would not reduce in size when the message was discarded. PuppetDB 5.1.5 correctly reduces the command queue size when a malformed message is submitted to the queue. ([PDB-3830](https://tickets.puppetlabs.com/browse/PDB-3830))

-   When the PE package inspector is configured, duplicate package data can sometimes be submitted from the `gem` and similar package providers. PuppetDB 5.1.5 now removes these duplicates, instead of rejecting the data as in previous versions of PuppetDB. ([PDB-3862](https://tickets.puppetlabs.com/browse/PDB-3862))

### Contributors
Andrew Roetker, Garrett Guillotte, Molly Waggett, Morgan Rhodes,
Nekototori, Nick Walker, Rob Browning, Russell Mull, and Zak Kent

## 5.1.4

PuppetDB 5.1.4 is a bug-fix release, and adds packages for Debian 9 ("Stretch").

### Bug fixes

-   PuppetDB's `jackson-databind` dependency is updated to 2.9.1, which contains a fix to a security issue. This library is only used in the structured logging module, so most users should be unaffected.

### Contributors
Molly Waggett, Russell Mull, Sara Meisburger, Wyatt Alt, and Zak Kent

5.1.3
-----

PuppetDB 5.1.3 includes bugfixes and performance improvements.

### Bug Fixes
* A recent fact data migration should no longer crash when the existing
  data has unexpected null value representations.
  ([PDB-3692](https://tickets.puppetlabs.com/browse/PDB-3692))

### Improvements
* An optional facts blacklist feature has been added to the PDB config file
  that allows users to specify facts that will be ignored during ingestion.
  ([PDB-3630](https://tickets.puppetlabs.com/browse/PDB-3630))

### Contributors
Morgan Rhodes, Rob Browning, Russell Mull, and Zak Kent

5.1.1
-----
PuppetDB 5.1.1 is a bugfix release.

### Bug Fixes
* This release contains a fix for a uniqueness bug in the recent fact data
  migration.
  ([PDB-3692](https://tickets.puppetlabs.com/browse/PDB-3692))

### Contributors
Morgan Rhodes, Rob Browning, Russell Mull, and Zak Kent

5.1.0
-----

PuppetDB 5.1.0 is a bugfix and performance release. It contains significant
schema migrations, most notably for fact storage. It also improves handling of
binary data in several places.

### Upgrading

* Before upgrading you should ensure you have as much free disk space as you
  have data. Database schema changes (also known as migrations) may require
  temporary duplication of table data and may mean you have to wait during the
  upgrade as these migrations run.

### Improvements

* Improve data storage performance by optimizing the query which
  updates the contents of the fact_values table.
  ([PDB-3639](https://tickets.puppetlabs.com/browse/PDB-3639))

* PuppetDB no longer sends redundant "SET TRANSACTION READ COMMITTED"
  commands to the database.
  ([PDB-3195](https://tickets.puppetlabs.com/browse/PDB-3195))

### Bug Fixes

* PuppetDB 4.4.x and 5.0.x updated fact storage to optimize write throughput. It
  unfortunately had negative consequences on query performance under heavy load.
  This rolls back to the older (4.3.x) fact storage style, which has worse write
  performance but better query performance.
  ([PDB-3611](https://tickets.puppetlabs.com/browse/PDB-3611))

* PuppetDB has historically had issues dealing with binary data. Specifically,
  unicode-escaped null characters cannot be stored in PostgreSQL. The release
  directly configures the JSON library used by PuppetDB to replace such
  characters, which should completely eliminate the problem in a large class of
  cases. ([PDB-3648](https://tickets.puppetlabs.com/browse/PDB-3648))

* The puppetdb terminus would fail if the catalog contained resources using the
  "alias" metaparameter, causing the agent run to fail. This is a regression
  introduced in Puppet 5.
  ([PDB-3620](https://tickets.puppetlabs.com/browse/PDB-3620))

* Some text fields in puppetdb (notably report.config_version) had
  artificial size limits applied to them in the database schema. These limits
  have been removed.
  ([PDB-3400](https://tickets.puppetlabs.com/browse/PDB-3400))

* PuppetDB now filters null bytes in Resource Events fields; this fixes a
  problem when dealing with certain entries in the Windows registry which
  contain the value '\0' by default.
  ([PDB-3058](https://tickets.puppetlabs.com/browse/PDB-3058))

### Contributors
Britt Gresham, Dan Lidral-PorterJoe Pinsonault, Jorie Tappa, Josh Cooper,
Melissa Stone, Mike Eller, Rob Browning, Romain Tartière, Russell Mull,
Scott Walker, and Wyatt Alt

5.0.1
-----

PuppetDB 5.0.1 is a major release with new features, improved
performance, and some backward incompatibilities.  Notable
incompatibilities include the requirement of Puppet Server 5 or newer,
Puppet Agent 5 or newer, JVM 8 or newer, PostgreSQL 9.6 or newer, and
the fact that the root query endpoint now filters inactive nodes by
default.

For anyone tracking PuppetDB development at
https://github.com/puppetlabs/puppetdb, note that as of this release,
there will no longer be a stable branch.  All development for older
"Y" releases (X.Y.Z) will happen on the relevant version branch, for
example (4.4.x).

### Upgrading

* PuppetDB requires PostgreSQL 9.6 or newer.  Support for versions
  prior to 9.6 have been retired.  Please upgrade PostgreSQL to 9.6 or
  newer before upgrading PuppetDB, and please enable the
  [pg\_trgm][pg\_trgm] extension, as explained
  [here][configure\_postgres].  The official PostgreSQL repository
  packages are recommended.  See the
  [YUM](https://wiki.postgresql.org/wiki/YUM\_Installation)
  instructions or the [Apt](https://wiki.postgresql.org/wiki/Apt)
  instructions for further details.
  ([PDB-2950](https://tickets.puppetlabs.com/browse/PDB-2950))

* Before upgrading, make sure all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Before upgrading you should ensure you have as much free disk space
  as you have data. Database schema changes (also known as migrations)
  may require temporary duplication of table data and may mean you
  have to wait during the upgrade as these migrations run.

* PuppetDB is no longer compatible with agent and server versions
  older than 5.0.0.
  ([PDB-3558](https://tickets.puppetlabs.com/browse/PDB-3558))

* PQL queries and structured queries issued to the root query endpoint
  now automatically exclude deactivated and expired nodes.  This has
  always been the case for other query endpoints, and is usually what
  you want.  To target only inactive nodes, you can specify
  `node_state = 'inactive'`; for all both active and inactive, use
  `node_state = 'any'`.
  ([PDB-3420](https://tickets.puppetlabs.com/browse/PDB-3420))

### Downgrading

* If you attempt to downgrade to a previous version of PuppetDB, in addition to
  wiping the database you will need to clear the queue by deleting
  your `vardir`.

### Deprecations and retirements

* The deprecated `dlo-compression-interval` and
  `dlo-compression-threshold` options have been removed.
  ([PDB-3552](https://tickets.puppetlabs.com/browse/PDB-3552))

* The previously deprecated "puppetdb import" and "puppetdb export"
  command line tools have been retired.  They have been replaced with
  the "puppet db import" and "puppet db export" commands, which are
  included in puppet-client-tools.  See
  https://docs.puppet.com/puppetdb/latest/pdb\_client\_tools.html and
  https://docs.puppet.com/puppetdb/latest/anonymization.html for
  further information.
  ([PDB-3306](https://tickets.puppetlabs.com/browse/PDB-3306))

### Improvements

* PuppetDB ships with better defaults for `node-purge-ttl` and
  `node-ttl`.  `node-purge-ttl` defaults to 14 days if left
  unconfigured so that old unused information is automatically flushed
  from the database.  `node-ttl` defaults to 7 days, auto-expiring
  nodes which haven't checked in in a week.  These changes match the
  existing behavior of Puppet Enterprise.  To retain the old behavior,
  set `node-ttl` and `node-purge-ttl` to "0s" in your PuppetDB
  configuration file.
  ([PDB-3318](https://tickets.puppetlabs.com/browse/PDB-3318))

* PuppetDB no longer purges all eligible nodes during its periodic
  garbage collections.  Now it only purges up to 25 each time, but
  this value can be adjusted by the `node-purge-gc-batch-limit`
  configuration setting.  It is now also possible to specify a
  purge\_nodes batch\_limit for admin/cmd endpoint clean commands.
  ([PDB-3546](https://tickets.puppetlabs.com/browse/PDB-3546))

* Report storage has been optimized by requiring fewer tables to be
  consulted when updating the record of the latest report.
  ([PDB-3529](https://tickets.puppetlabs.com/browse/PDB-3529))

* The performance of some queries involving each node's latest report
  has been improved.
  ([PDB-3518](https://tickets.puppetlabs.com/browse/PDB-3518))

* PuppetDB was using more memory than necessary when creating query
  results, which could occasionally result in out-of-memory errors
  when performing queries with very large result sets.  Its memory
  footprint should now be lower overall, and those errors should no
  longer occur.
  ([PDB-3467](https://tickets.puppetlabs.com/browse/PDB-3467))

* PuppetDB should now shut down with a non-zero exit status when it
  detects an unsupported PostgreSQL version.  Previously it might hang
  while trying to exit.
  ([PDB-3541](https://tickets.puppetlabs.com/browse/PDB-3541))

* Command processing errors due to malformed data now cause the
  command to be delivered to the discard directory (dead letter
  office), instead of remaining in the queue forever.
  ([PDB-3566](https://tickets.puppetlabs.com/browse/PDB-3566))

* PuppetDB was leaving unused "edge" records in the database.  For
  most users this had very little impact, but they could pile up over
  time as nodes are purged if
  [node-purge-ttl](https://docs.puppet.com/puppetdb/5.0/configure.html#node-purge-ttl)
  is non-zero.  The unused edges will now be removed on upgrade and
  continuously cleaned up when they are no longer needed.
  ([PDB-3515](https://tickets.puppetlabs.com/browse/PDB-3515))

* PuppetDB no longer prints a warning when a command request doesn't
  include a Content-Length header, because it's less important with the
  stockpile queue.
  ([PDB-3567](https://tickets.puppetlabs.com/browse/PDB-3567))

* PuppetDB now filters null bytes in Resource Events fields.  This
  fixes a problem when dealing with certain entries in the Windows
  registry which contain the value '\0' by default.
  ([PDB-3058](https://tickets.puppetlabs.com/browse/PDB-3058))

* A change in puppet's handling of resource titles
  ([PUP-7605](https://tickets.puppetlabs.com/browse/PUP-7605)) has
  been accommodated.
  ([PDB-3595](https://tickets.puppetlabs.com/browse/PDB-3595))

### Contributors

Adrien Thebo, Andrew Roetker, Gert Drapers, Jennifer Shehane, Jeremy
Barlow, John Duarte, Michelle Fredette, Moses Mendoza, Nick Fagerlund,
Nick Walker, Rob Browning, Russell Mull, Ruth Linehan, Ryan Senior,
Spencer McElmurry, Thomas Hallgren, Wayne Warren, Wyatt Alt, and Zak
Kent

5.0.0
-----

PuppetDB 5.0.0 was not released due to a packaging error.
