---
title: "PuppetDB 5.2: Release notes"
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

## PuppetDB 5.2.8

### New features

- **New Puppet Query Language (PQL) operators.** You can now use the negative operators `!=` and `!~`. [PDB-3471](https://tickets.puppetlabs.com/browse/PDB-3471)

### Bug fixes

- **Logback integration.** The Logstash dependency was accidentally removed in a previous release. The dependency has been reinstated. [PDB-4277](https://tickets.puppetlabs.com/browse/PDB-4277)

- **Improved** `certname` **handling.** Previously, PuppetDB was unable to process commands that were submitted with a `certname` containing special characters such as `/`, `/`, `:` `_` or `0`, or exceeded 200 UTF-8 bytes if PuppetDB was restarted after the commands were submitted and before they were processed. PuppetDB now handles these commands correctly. [PDB-4257](https://tickets.puppetlabs.com/browse/PDB-4257)

- **Errors when using the** `in` **operator with** `"act_only":true`. Valid PQL queries, which use the `in` operator to compare against an array, that are being converted to AST via the `ast_only` option no longer throw a `NullPointerException`.  [PDB-4232](https://tickets.puppetlabs.com/browse/PDB-4232)

- **Errors when using the** `in` **operator with arrays**. PuppetDB would give an error if you used the `in` operator with an array of fact values or any array that did not have just one element. PuppetDB now accepts an array of fact values unless it finds an actual type mismatch. [PDB-4199](https://tickets.puppetlabs.com/browse/PDB-4199)

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

###Contributors
Austin Blatt, Britt Gresham, Claire Cadman, Ethan J. Brown, Garrett Guillotte, Jarret Lavallee, Molly Waggett, Morgan Rhodes, Rob Browning, Robert Roland, and Zachary Kent

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

Garrett Guillotte, Morgan Rhodes, Rob Browning, and Zachary Kent

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
Andrew Roetker, Garrett Guillotte, and Zachary Kent

## 5.2.1

PuppetDB 5.2.1 is a bug-fix release that improves facts query performance and improves command queue management.

### Bug fixes

-   PuppetDB 5.2.1 significantly improves the performance of facts queries that constrain 'name' to a single value, such as `facts [value, count()] { name = 'osFamily' group by value }`. ([PDB-3838](https://tickets.puppetlabs.com/browse/PDB-3838))

-   If you submitted a malformed message to the command queue in previous versions of PuppetDB, the command queue would grow upon receiving the message but would not reduce in size when the message was discarded. PuppetDB 5.2.1 correctly reduces the command queue size when a malformed message is submitted to the queue. ([PDB-3830](https://tickets.puppetlabs.com/browse/PDB-3830))

### Contributors
Garrett Guillotte, Morgan Rhodes, Nick Walker, Rob Browning, Russell
Mull, and Zachary Kent

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
Scott Walker, Thomas Hallgren, Wyatt Alt, and Zachary Kent

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

## 5.1.5

PuppetDB 5.1.5 is a bug-fix release.

### Bug fixes

-   Passing the `node_state` filter criteria as part of a conjuction operator (`and` and `or`) didn't work in previous versions of PuppetDB. PuppetDB 5.1.5 resolves this issue. ([PDB-3808](https://tickets.puppetlabs.com/browse/PDB-3808))

-   If you submitted a malformed message to the command queue in previous versions of PuppetDB, the command queue would grow upon receiving the message but would not reduce in size when the message was discarded. PuppetDB 5.1.5 correctly reduces the command queue size when a malformed message is submitted to the queue. ([PDB-3830](https://tickets.puppetlabs.com/browse/PDB-3830))

-   When the PE package inspector is configured, duplicate package data can sometimes be submitted from the `gem` and similar package providers. PuppetDB 5.1.5 now removes these duplicates, instead of rejecting the data as in previous versions of PuppetDB. ([PDB-3862](https://tickets.puppetlabs.com/browse/PDB-3862))

### Contributors
Andrew Roetker, Garrett Guillotte, Molly Waggett, Morgan Rhodes,
Nekototori, Nick Walker, Rob Browning, Russell Mull, and Zachary Kent

## 5.1.4

PuppetDB 5.1.4 is a bug-fix release, and adds packages for Debian 9 ("Stretch").

### Bug fixes

-   PuppetDB's `jackson-databind` dependency is updated to 2.9.1, which contains a fix to a security issue. This library is only used in the structured logging module, so most users should be unaffected.

### Contributors
Molly Waggett, Russell Mull, Sara Meisburger, Wyatt Alt, and Zachary Kent

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
Morgan Rhodes, Rob Browning, Russell Mull, and Zachary Kent

5.1.1
-----
PuppetDB 5.1.1 is a bugfix release.

### Bug Fixes
* This release contains a fix for a uniqueness bug in the recent fact data
  migration.
  ([PDB-3692](https://tickets.puppetlabs.com/browse/PDB-3692))

### Contributors
Morgan Rhodes, Rob Browning, Russell Mull, and Zachary Kent

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

4.4.1
-----

PuppetDB 4.4.1 was a PE-only bugfix release.

4.4.0
-----

PuppetDB 4.4.0 is a backward-compatible feature release that is primarily
focused on improving command processing performance.

### New features / Enhancements

* When a new command is received which completely supersedes one that is sitting
  in the queue, the old one is dropped. This should considerably improve
  processing time for 'replace facts' and 'replace catalog' commands when the
  queue is backed up. ([PDB-3108](https://tickets.puppetlabs.com/browse/PDB-3108))

* Fact storage is now optimized for storage speed, at the cost of additional
  disk usage. We found that our previous design, which focused on reducing the
  on-disk size of fact data, caused performance problems in large installations
  and with certain forms of structured facts. With this modest denormalization,
  fact storage performance is more consistent at large scale and in these corner
  cases. In exchange for the improved performance, the storage space required
  for facts will increase during the upgrade. The exact increase will depend on
  the duplication rate of the existing fact values.
  ([PDB-3249](https://tickets.puppetlabs.com/browse/PDB-3249))

* Commands sent from Puppet Server to PuppetDB will be gzip-compressed on the
  wire and on disk when running with the upcoming Puppet Server 5.0.
  ([PDB-2640](https://tickets.puppetlabs.com/browse/PDB-2640))

* Command logging now includes the elapsed time for processing each command.
  This should make it easier to diagnose performance problems which are related
  to data coming from a subset of nodes.
  ([PDB-3096](https://tickets.puppetlabs.com/browse/PDB-3096))

* Parameters which are marked with the 'Sensitive' datatype in your puppet
  manifests are now redacted before being sent to PuppetDB.
  ([PDB-3322](https://tickets.puppetlabs.com/browse/PDB-3322))

* PQL and the root query endpoint are no longer considered experimental.
  ([PDB-3256](https://tickets.puppetlabs.com/browse/PDB-3256))

* Added an implicit query relationship between the 'inventory' and 'facts'
  entities. This allows PQL queries like this:

      facts[value]{name = "ipaddress" and inventory{trusted.extensions.foo = "bar"}}

  ([PDB-3099](https://tickets.puppetlabs.com/browse/3309))

* Unify implicit relationships for all entities with a 'certname' field.
  ([PDB-3367](https://tickets.puppetlabs.com/browse/PDB-3367))

### Bug fixes and maintenance:

* The factset entity's 'producer' field was not being updated for pre-existing
  factsets. ([PDB-3276](https://tickets.puppetlabs.com/browse/PDB-3276))

* Determine the latest report using the `producer_timestamp` field from the
  compile master, not `end_time` from the agent.
  ([PDB-3277](https://tickets.puppetlabs.com/browse/PDB-3277))

* Fix regular expression queries on nested resource parameters.
  ([PDB-3285](https://tickets.puppetlabs.com/browse/PDB-3285))

* Ensure that the queue-depth metric accurately reflects the data on disk when
  the files are directly removed. ([PDB-3307](https://tickets.puppetlabs.com/browse/PDB-3307))

* Allow paging options to be specified both in the request parameters and in an
  outer `["from"]` clause.
  ([PDB-3379](https://tickets.puppetlabs.com/browse/PDB-3379))

* Drop the unneeded `resource_events_resource_type_idx` index.
  ([PDB-3220](https://tickets.puppetlabs.com/browse/PDB-3220))

### Contributors

Andreas Paul, Andrew Roetker, Brian Cain, Dan Lidral-Porter, Jeremy Barlow, Ken
Barber, Morgan Rhodes, Nick Walker, Rob Browning, Russell Mull, Ruth Linehan,
Ryan Senior, Wyatt Alt

4.3.2
-----

PuppetDB 4.3.2 is a bugfix release to fix a report storage performance
regression in versions 4.2.4, 4.2.5, 4.3.0, and 4.3.1. All affected users are
encouraged to upgrade to this release.

### Bug fixes and maintenance:

* When storing reports, PuppetDB first queries the database to see if a report
  with the same hashcode is already present. This database query was erroneously
  not using the index on the hash column, resulting in increased report storage
  time. It has been updated so that the index will take effect as intended.
  ([PDB-3323](https://tickets.puppetlabs.com/browse/PDB-3323))

### Contributors

Jessykah Bird, Russell Mull

4.3.1
-----

PuppetDB 4.3.1 was a PE-only bugfix release.

4.3.0
-----

PuppetDB 4.3.0 is a backward-compatible feature release that changes
the way PuppetDB handles incoming commands.  Previously they were
stored in ActiveMQ for future processing, but now they're being
handled by [stockpile][stockpile].  This should increase performance
and may decrease the maximum heap required for a given workload.
Please see the [queue information][queue_support_guide] in the support
guide if you're interested in the details.

### New features / Enhancements

* PuppetDB now stores incoming commands in [stockpile][stockpile]
  rather than ActiveMQ.
  ([PDB-2730](https://tickets.puppetlabs.com/browse/PDB-2730))

### Contributors

Andrew Roetker, Dan Lidral-Porter, Jeremy Barlow, Karel Březina, Ken Barber,
Kylo Ginsberg, Molly Waggett, Rob Browning, Ryan Senior, Tiffany
Longworth, and Wyatt Alt.

4.2.4
-----

PuppetDB 4.2.4 is a minor bugfix release.

### Bug fixes and maintenance:

* Fixed a bug affecting in clauses with an array on fact values ([PDB-2330](https://tickets.puppetlabs.com/browse/PDB-2330))

* Added code to check for existing reports and ignore duplicates ([PDB-2939](https://tickets.puppetlabs.com/browse/PDB-2939))

* Changed the dashboard to cache PDB version rather than querying for
  it every 5 seconds ([PDB-2836](https://tickets.puppetlabs.com/browse/PDB-2836))

* Added a warning if retired server/port config items are in use ([PDB-3071](https://tickets.puppetlabs.com/browse/PDB-3071))

* Switched log file rotation to be based on size, not date ([PDB-3077](https://tickets.puppetlabs.com/browse/PDB-3077))

* Added missing inventory relationships to other entities allowing
  implicit joins on inventory ([PDB-2962](https://tickets.puppetlabs.com/browse/PDB-2962))

* Fixed a bug where version 6 reports incorrectly rejected ([PDB-3064](https://tickets.puppetlabs.com/browse/PDB-3064))

* Added validation on content-type of POST requests ([PDB-2851](https://tickets.puppetlabs.com/browse/PDB-2851))

* Removed memoization of resource identity hashes. The memoization was based
  on count, not overall pool size so it could consume arbitrary
  RAM. It was responsible for overrunning a 32GB JVM heap in the field ([PDB-3065](https://tickets.puppetlabs.com/browse/PDB-3065))

* Updated the default sample data to include new data added in by the
  most recent wire-format revisions ([PDB-2810](https://tickets.puppetlabs.com/browse/PDB-2810))

* Improve event-counts and aggregate-event-counts endpoint to provide
  information about corrective changes (PE only) ([PDB-3047](https://tickets.puppetlabs.com/browse/PDB-3047))

* Added a partial index in resource_events for querying corrective events (PE only)

* Added support for querying resource events by corrective_change flag (PE only)

* Updated the following dependencies

  - clj-i18n 0.4.2 -> 0.4.3
  - beaker 2.43.0 -> 2.50.0
  - EZBake 0.5.1 -> 1.1.2
  - trapperkeeper 1.4.1 -> 1.5.1
  - tk-status 0.4.0 -> 0.5.0

### Documentation:

* Updated docs to clarify the effect of setting the time to live
settings to '0s'

### Contributors

Andrew Roetker, Jeremy Barlow, Karel Březina, Ken Barber, Kylo
Ginsberg, Melissa Stone, Molly Waggett, Rob Browning, Ruth Linehan,
Ryan Senior, Tiffany Longworth, and Wyatt Alt.

4.2.3
-----
PuppetDB 4.2.3 was not publically released.

4.2.2
-----
PuppetDB 4.2.2 is a minor bugfix release.

### Bug fixes:

* Migrations from versions before 4.2.0 should no longer cause out of memory
  errors on small heaps or large databases.
  ([PDB-2997](https://tickets.puppetlabs.com/browse/PDB-2997))

* Our internationalization library uses less CPU than before.
  ([PDB-2985](https://tickets.puppetlabs.com/browse/PDB-2985))

### Documentation:

* PQL documentation has been updated with more examples and better structure.
  ([ORCH-1491](https://tickets.puppetlabs.com/browse/ORCH-1491))

### Contributors

Ken Barber, Melissa Stone, Rob Browning, Ryan Senior, Wyatt Alt


4.2.1
-----
PuppetDB 4.2.1 is a minor bugfix release.

### Bug fixes:

* Fix a regression in 4.2.0 that broke single-argument usage of "and" and "or"
  in the query language.
  ([PDB-2946](https://tickets.puppetlabs.com/browse/PDB-2946))

* Ensure that the `noop` field is set on reports from all supported Puppet
  agent versions, correcting a regression in 4.2.0 that caused it to be null in
  some cases. (PE-17059 [internal])

### Contributors

Karel Březina, Molly Waggett, Ryan Senior, Wyatt Alt

4.2.0
-----

PuppetDB 4.2.0 is a backward-compatible feature release that adds a
new inventory endpoint, the ability to query structured data (like
facts and resource parameters) using a dotted notation and the ability
to trigger background/GC tasks manually via POST. This release
also includes several new queryable fields, bugfixes and faster
retries for common error scenarios.

### New features / Enhancements

* Added a new inventory endpoint that includes all fact keypairs
  represented as a map. Trusted facts are also returned as a map in a
  separate keypair. This new data can be queried via our "dotted
  syntax" in both AST and PQL query languages. More information is
  available via the [API docs](./api/query/v4/inventory.html).
  ([PDB-2632](https://tickets.puppetlabs.com/browse/PDB-2632))

* Added support for "dotted query" syntax to resource
  parameters. Support for this is available in both AST and PQL query
  langauges. ([PDB-2632](https://tickets.puppetlabs.com/browse/PDB-2632))

* Added a new `ast_only` parameter to the root query endpoint
  that translates a PQL query to an equivalent AST query. More
  information is available in the [root
  endpoint](./api/query/v4/index.html#url-parameters) docs.
  ([PDB-2528](https://tickets.puppetlabs.com/browse/PDB-2528))

* Added "quick retries" on command failures. This will retry commands
  (such as storing a new report or updating a catalog) immediately
  after a failure. The command will be retried 4 times before falling
  back to the regular retry process that involves delaying the message
  and reenqueuing it. This should result in faster command processing
  times and use less disk I/O in the most common kinds of failures.
  ([PDB-2865](https://tickets.puppetlabs.com/browse/PDB-2865))

* Added an endpoint that can trigger PuppetDB GC processes via a POST
  (like purging old reports or deactivated nodes).  With that, you can
  disable the automatic scheduling of those processes, and run them
  selectively, perhaps via cron, to avoid increasing the load during
  peak usage times.  See the [admin command](./api/admin/v1/cmd.html)
  endpoint documentation for more information.
  ([PDB-2478](https://tickets.puppetlabs.com/browse/PDB-2478))

* Added `depth` as a queryable fact-path endpoint field.
  ([PDB-2771](https://tickets.puppetlabs.com/browse/PDB-2771))

* Began storing the new `remediation` field, which can be queried via
  the reports and nodes endpoints.
  ([PDB-2838](https://tickets.puppetlabs.com/browse/PDB-2838))

* Began allowing whitespace around parentheses in PQL.
  ([PDB-2891](https://tickets.puppetlabs.com/browse/PDB-2891))

* Added a `producer` field to catalogs, facts and reports which
  provides the certname of the puppetmaster that sent the data.
  ([PDB-2782](https://tickets.puppetlabs.com/browse/PDB-2782),
  [PDB-2837](https://tickets.puppetlabs.com/browse/PDB-2837))

* Switched to using the real noop status sent by the agent, previous
  versions of PuppetDB inferred that noop status by looking for noop
  on individual resources.
  ([PDB-2753](https://tickets.puppetlabs.com/browse/PDB-2753))

* Added the 'noop_pending' field, which is true when the report
  contains resources that have a noop status.
  ([PDB-2753](https://tickets.puppetlabs.com/browse/PDB-2753))

### Bug fixes and maintenance

* Fixed nested implicit subqueries against different
  entities. Previously this would have raised an exception.
  ([PDB-2892](https://tickets.puppetlabs.com/browse/PDB-2892))

* Fixed a bug preventing usage of functions on fact-contents queries.
  ([PDB-2843](https://tickets.puppetlabs.com/browse/PDB-2843))

* Improved error messages for invalid subquery operators.
  ([PDB-2310](https://tickets.puppetlabs.com/browse/PDB-2310))

* Updated PuppetDB node check-ins to hourly rather than only on startup.

* Updated the following dependencies

  - trapperkeeper 1.3.1 -> 1.4.1
  - trapperkeeper-jetty9 1.3.1 -> 1.4.1
  - trapperkeeper-status 0.3.1 -> 0.4.0
  - prismatic/schema 1.0.4 -> 1.1.2
  - Ezbake 0.3.25 -> 0.4.3 ([PDB-2910](https://tickets.puppetlabs.com/browse/PDB-2910))

4.1.4
-----

PuppetDB 4.1.4 was not publicly released.

4.1.3
-----

PuppetDB 4.1.3 was not publicly released.

4.1.2
-----

PuppetDB 4.1.2 is a backward-compatible bugfix release that speeds up node
queries, fixes a bug that slowed garbage collection, and improves error logging.

### Bug Fixes and Maintenance

* Queries to the nodes endpoint should be faster, due to the removal of the
  index on certnames(latest_report_id).
  ([PDB-2789](https://tickets.puppetlabs.com/browse/PDB-2789))

* Fixed a regression caused by garbage collection related changes
  ([PDB-2477](https://tickets.puppetlabs.com/browse/PDB-2477)) which caused a
  high IO load on PostgreSQL.

* Command failure messages now log the certname of the node from which the
  command originated in order to track problematic commands to individual nodes.
  ([PDB-2580](https://tickets.puppetlabs.com/browse/PDB-2580))

* Some previously masked garbage collection will now appear in the logs as errors.
  ([PDB-2704](https://tickets.puppetlabs.com/browse/PDB-2704))

### Documentation

* Documentation for how to use RBAC tokens (PE only) with PuppetDB has been added.
  ([PDB-2762](https://tickets.puppetlabs.com/browse/PDB-2762))

* Various other documentation issues have been fixed.

### Contributors

Andrew Roetker, Chris Cowell, Garrett Guillotte, Molly Waggett, Rob Browning,
Ryan Senior, and Wyatt Alt

4.1.0
-----

PuppetDB 4.1.0 is a backward-compatible feature release that adds packages for
Ubuntu Xenial - 16.04, Ubuntu Wily - 15.10, adds preliminary support for HUP signal
handling, and improves the speed of removing old reports and node expiration and purging.

### New features / Enhancements

* PuppetDB now has packages for Ubuntu Xenial - 16.04 and Ubuntu Wily - 15.10
  ([PDB-2475](https://tickets.puppetlabs.com/browse/PDB-2475)).

* Allow configuration of AciveMQ Broker's memoryLimit. For PuppetDB instances
  with larger amount of memory and heavy load, this can improve performance.
  More information in the [config docs](./configure.html#memory-usage)
  ([PDB-2726](https://tickets.puppetlabs.com/browse/PDB-2726)).

* Preliminary support for HUP signal handling. Note that due to
  [AMQ-5263](https://issues.apache.org/jira/browse/AMQ-5263) there is
  a possibility of a crash when HUPed. We've observed this race
  condition when under heavy load and repeatedly HUPed. This will be
  fixed and more robust in a future release
  ([PDB-2546](https://tickets.puppetlabs.com/browse/PDB-2546)).

* Added a `to_string` function to format strings in both PQL and the AST query
  language ([PDB-2494](https://tickets.puppetlabs.com/browse/PDB-2494)).

* Added more metrics related to command processing. The most useful is probably
  `message-persistence-time`, which tracks the time take to write the command to
  disk ([PDB-2485](https://tickets.puppetlabs.com/browse/PDB-2485)).

* Added the ability to use `latest_report?` when querying the older event query
  endpoints ([PDB-2527](https://tickets.puppetlabs.com/browse/PDB-2527)).

* Added a `cached_catalog_status` field to the nodes endpoint
  ([PDB-2586](https://tickets.puppetlabs.com/browse/PDB-2586)).

* Added a `latest_report_noop` field to the nodes endpoint
  ([PDB-2490](https://tickets.puppetlabs.com/browse/PDB-2490)).

* (PE Only) Added token-based authentication (RBAC support)
  ([PDB-2482](https://tickets.puppetlabs.com/browse/PDB-2482)).

### Bug fixes and maintenance

* Added scaffolding for internationization of PuppetDB log and error messages.
  This is the first step in supporting multiple languages in PuppetDB
  ([PDB-2547](https://tickets.puppetlabs.com/browse/PDB-2547)).

* Node expiration is now done in a single query, previously this required a
  database round-trip for each exipring node
  ([PDB-2576](https://tickets.puppetlabs.com/browse/PDB-2576)).

* Removing old reports (reports past their `report-ttl`) and purged nodes
  (`node-purge-ttl`) is now significantly faster due to pushing much of this
  work to the database and being more efficient with how constraints are applied
  ([PDB-2477](https://tickets.puppetlabs.com/browse/PDB-2477)).

* Added validation of the query parameter in the `puppetdb_query()` function
  included in the terminus
  ([PDB-2648](https://tickets.puppetlabs.com/browse/PDB-2648)).

* Error responses now include a proper content type of text/plain
  ([PDB-2621](https://tickets.puppetlabs.com/browse/PDB-2621)).

* Only set the noop flag on a report to true if all it's resource events are
  noops ([PDB-2586](https://tickets.puppetlabs.com/browse/PDB-2586)).

* PuppetDB has switched to using the trapperkeeper-metrics webservices. This
  should not change functionality as the code from PuppetDB has been moved into
  that shared library
  ([PDB-2573](https://tickets.puppetlabs.com/browse/PDB-2573)).

* PuppetDB is now testing against nightly puppetserver packages in addition to
  released puppetserver packages
  ([PDB-2501](https://tickets.puppetlabs.com/browse/PDB-2501)).

4.0.2
-----

PuppetDB 4.0.2 is a backward-compatible bugfix release that should
improve report processing performance, and fix a node-expiration bug,
a bug in the handling of some correlated subqueries, and a bug that
disallowed colons in request routes.

### Bug Fixes and Maintenance

* Report processing should be more efficient, after the removal of
  some redundant validations.
  ([PDB-2620](https://tickets.puppetlabs.com/browse/PDB-2620))

* Colons in routes should be allowed again (e.g. resources
  types/titles, etc.).  This was broken by the 4.0.0 release.
  ([PDB-2624](https://tickets.puppetlabs.com/browse/PDB-2624))

* Correlated subqueries (PQL or AST) involving fact values should work
  better.  Previously a query like this this would fail
  ([PDB-2474](https://tickets.puppetlabs.com/browse/PDB-2474)):

```
    resource {
      type = 'Package'
      and title in facts [value] { name = 'apache_package_name' }
    }
```

* Nodes should no longer be incorrectly eligible for expiration in an
  unlikely corner case.
  ([PDB-2617](https://tickets.puppetlabs.com/browse/PDB-2617))

* PuppetDB has migrated to ezbake 0.3.24.
  ([PDB-2645](https://tickets.puppetlabs.com/browse/PDB-2645))

* Debian 6 has reached End Of Life upstream and so is no longer being
  tested. ([PDB-2629](https://tickets.puppetlabs.com/browse/PDB-2629))


### Documentation

* "Puppet Labs" has been changed to "Puppet" where relevant.
  ([PDB-2592](https://tickets.puppetlabs.com/browse/PDB-2592))

* Various other documentation issues have been fixed.

### Contributors

Andrew Roetker, Ken Barber, Melissa Stone, Michelle Fredette, Morgan
Rhodes, Rob Browning, Ryan Senior, and Wyatt Alt

4.0.1
-----

PuppetDB 4.0.1 is a backward-compatible release that fixes database
connection issues related to the maximum-pool-size, and fixes a bug
that could cause a command involving a very large number of resources
to fail.  This version was not publicly released.

### Bug Fixes and Maintenance

* The maximum-pool-size configuration option should now work as
  intended.  It was ignored
  in 4.0.0. ([PDB-2581](https://tickets.puppetlabs.com/browse/PDB-2581))

* An unintentional limit on the number of resources in an internal
  resources-exists? call has been fixed.  The previous arrangement
  could cause a command involving a very large number of resources to
  fail.  ([PDB-2548](https://tickets.puppetlabs.com/browse/PDB-2548))

* The indirector tests now have access to the environment.
  ([PDB-2577](https://tickets.puppetlabs.com/browse/PDB-2577))

* A test for the idempotency of indexes! with alternate schemas has
  been added.
  ([PDB-2537](https://tickets.puppetlabs.com/browse/PDB-2537))

### Documentation

* The fact path query documentation has been improved.
  ([PDB-2570](https://tickets.puppetlabs.com/browse/PDB-2570))

* The release note links have been fixed.
  ([PDB-2567](https://tickets.puppetlabs.com/browse/PDB-2567))

* The web sidebar is now version-agnostic.

* Various other documentation issues have been fixed.

### Contributors

Andrew Roetker, Garrett Guillotte, Ken Barber, Nick Fagerlund, Ryan
Senior, and Wyatt Alt

4.0.0
-----

PuppetDB 4.0.0 is a major release which introduces new features and
some breaking changes. Highlights of the new features include a new
query language called PQL that is intended to be easier and simpler to
use along with several new query operators. Notable breaking changes
are dropping support for HyperSQL, Ruby 1.8.x and Puppet 3.x.

### Contributors

Andrew Roetker, Chris Cowell-Shah, Chris Price, Ken Barber, Michelle
Fredette, Mindy Moreland, Nick Fagerlund, Nick Walker, Rob Browning,
Russell Mull, Ryan Senior, Wyatt Alt


### Upgrading

* Users manually upgrading PuppetDB from PuppetDB 2.3.x should note that the
  `puppetdb-terminus` package was renamed to `puppetdb-termini` in PuppetDB
  version 3. Note also that like PuppetDB 3.x, PuppetDB 4.x requires Puppet 4.

  **Important note for RedHat users**: if you wish to use YUM (rather than the
  PuppetDB module) to upgrade to a specific version of `puppetdb-termini` (e.g.
  upgrading to PuppetDB 3.2.x instead of PuppetDB 4.x), you will need to
  uninstall `puppetdb-terminus` before installing `puppetdb-termini`, or `yum
  update puppetdb-terminus` will bring you to the latest `puppetdb-termini`
  instead of the one you want.

  No matter what OS you use, you will need to restart Puppet Server after
  installing puppetdb-termini. These steps (including the RedHat terminus
  uninstallation) are handled automatically in the version 5 [PuppetDB
  module][puppetdb-module].

* This version of PuppetDB uses the AIO layout (introduced in Puppet 4) to be
  consistent with other Puppet Labs layouts, such as Puppet Server. We
  recommend the using the [PuppetDB module][puppetdb-module] version 5, as it
  will automate much of this kind of management and has been modified
  specifically for Puppet 4 support by default.

  For those who don't use the PuppetDB module and are still on PuppetDB 2.3.x,
  this means the following paths have changed:

    * Primary configuration: /etc/puppetdb is now /etc/puppetlabs/puppetdb
    * Logs: /var/log/puppetdb is now /var/log/puppetlabs/puppetdb
    * Binaries: /usr/bin is now /opt/puppetlabs/bin
    * Data: /var/lib/puppetdb is now /opt/puppetlabs/server/data/puppetdb
    * Rundir: /var/run/puppetdb is now /var/run/puppetlabs/puppetdb

  The upgrade will automatically migrate your configuration, but if you
  encounter issues you should double check that the new pathing is in place.

* PuppetDB no longer supports HyperSQL. Users must upgrade to PostgreSQL 9.4 or
  newer before upgrading PuppetDB. For specific instructions on how to migrate
  data from HyperSQL to PostgreSQL, see our [migrating documentation][migrate].
  Users should also enable the [`pg_trgm`][pg_trgm] extension, as explained in
  the documentation on [postgres configuration][configure_postgres]. The
  official PostgreSQL repositories are recommended for packaging. See the
  [YUM](https://wiki.postgresql.org/wiki/YUM_Installation) instructions or the
  [Apt](https://wiki.postgresql.org/wiki/Apt) instructions for more details.

* Before upgrading, make sure all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Before upgrading you should ensure you have as much free disk space as you
  have data. Database schema changes (also known as migrations) may require
  temporary duplication of table data and may mean you have to wait during the
  upgrade as these migrations run.

### Downgrading

* If you attempt to downgrade to a previous version of PuppetDB, in addition to
  wiping the database you will need to clear the queue directory as described
  in the [documentation on KahaDB corruption][kahadb_corruption]. Otherwise you
  might see an IOException during startup when the older PuppetDB (using an
  older version of KahaDB) tries to read leftover, incompatible queue data.

### Breaking changes

This release retires support for Ruby 1.8.x, Puppet 3.x and HyperSQL. More
details on other breaking changes are below

* Retired Ruby 1.8.x and Puppet 3.x support in the PuppetDB terminus
  ([PDB-1956](https://tickets.puppetlabs.com/browse/PDB-1956),
  [PDB-1957](https://tickets.puppetlabs.com/browse/PDB-1957),
  [PDB-1100](https://tickets.puppetlabs.com/browse/PDB-1100),
  [PDB-841](https://tickets.puppetlabs.com/browse/PDB-841))

* Retired HyperSQL support from PuppetDB
  ([PDB-1996](https://tickets.puppetlabs.com/browse/PDB-1996))

* Retired catalog-hash-conflict-debugging from PuppetDB configuration
  ([PDB-1931](https://tickets.puppetlabs.com/browse/PDB-1931))

* The `hash` field of factsets is no longer nullable
  ([PDB-1014](https://tickets.puppetlabs.com/browse/PDB-1014))

* Added a `request_type` param to the Http.action in the PuppetDB
  terminus ([PDB-2070](https://tickets.puppetlabs.com/browse/PDB-2070))

* Retired the `server` and `port` settings in the PuppetDB terminus (use
  `server_urls` instead)
  ([PDB-2101](https://tickets.puppetlabs.com/browse/PDB-2101))

* Retired the cli version of the anonymizer tool (use the export tool
  flag instead) ([PDB-2036](https://tickets.puppetlabs.com/browse/PDB-2036))

* Upgraded to metrics-clojure `2.6.0`. This has caused our JMX beans to be
  renamed. See the [metrics documentation][metrics] for more info
  ([PDB-2234](https://tickets.puppetlabs.com/browse/PDB-2234))


#### Deprecations

* Deprecated cli import/export commands, to be replaced by a
  soon-to-be-released PuppetDB CLI tool
  ([PDB-2355](https://tickets.puppetlabs.com/browse/PDB-2355))

* Added deprecation warnings for old command versions. This only affects
  users submitting commands directly to PuppetDB, rather than through
  the terminus ([PDB-2433](https://tickets.puppetlabs.com/browse/PDB-2433))


### New features

* **Highlight**: We have added a new query language intended to be simpler and
  easier for users than our existing AST based language. This query language
  supports the same operators, fields, and entities as the AST language, but
  does so in a more concise way. See the [PQL tutorial][pqltutorial] for an
  introduction to the new language. We have also added a `puppetdb_query`
  function to our terminus that allows users to query PuppetDB with either
  language from within Puppet code.
  ([PDB-1799](https://tickets.puppetlabs.com/browse/PDB-1799),
  [PDB-2186](https://tickets.puppetlabs.com/browse/PDB-2186))

* We added a `root` single query endpoint at `/pdb/query/v4` and a `from`
  operator for querying any entity from a single endpoint
  ([PDB-2122](https://tickets.puppetlabs.com/browse/PDB-2122))

* We added an `array` operator to support use of the `in` operator against
  array literals. This is available in both the AST and PQL syntaxes.
  ([PDB-163](https://tickets.puppetlabs.com/browse/PDB-163))

* We now have a `[developer]` config section with an option to pretty print
  JSON API responses by default.
  ([PDB-2133](https://tickets.puppetlabs.com/browse/PDB-2133))

* We now support including paging operators in the query message body
  ([PDB-2812](https://tickets.puppetlabs.com/browse/PDB-2812))

* Added the `/admin/v1/summary-statistics` endpoint, which returns information
  about database usage
  ([PDB-2381](https://tickets.puppetlabs.com/browse/PDB-2381))

* The PuppetDB terminus now includes a utility function for querying PuppetDB
  (through the HA aware terminus) via puppet code
  ([PDB-2186](https://tickets.puppetlabs.com/browse/PDB-2186))

* POSTed queries now support the `pretty` parameter
  ([PDB-2438](https://tickets.puppetlabs.com/browse/PDB-2438))

* PuppetDB can now be configured to reject commands that are too large to fit
  in memory via the `reject-large-commands` config option. This new config
  option defaults to `false`
  ([PDB-2454](https://tickets.puppetlabs.com/browse/PDB-2454))

* We have added the new fields `catalog_uuid` and `code_id` to catalog and
  report storage. The new `cached_catalog_reason` field has been added to
  reports. All of these fields are also queryable via the `catalogs` and
  `reports` endpoints
  ([PDB-2126](https://tickets.puppetlabs.com/browse/PDB-2126))

* (PE Only) Added support for storing catalogs historically
  ([PDB-2127](https://tickets.puppetlabs.com/browse/PDB-2127),
  [PDB-2264](https://tickets.puppetlabs.com/browse/PDB-2264),
  [PDB-2286](https://tickets.puppetlabs.com/browse/PDB-2286),
  [PDB-2380](https://tickets.puppetlabs.com/browse/PDB-2380),
  [PDB-2290](https://tickets.puppetlabs.com/browse/PDB-2290))

* Added a status API served at `/status/v1/services` (now a standard of Puppet
  services) ([PDB-2098](https://tickets.puppetlabs.com/browse/PDB-2098),
  [PDB-2107](https://tickets.puppetlabs.com/browse/PDB-2107))

* Added the ability to broadcast commands to all `server_urls` configured in
  the PuppetDB terminus
  ([PDB-2070](https://tickets.puppetlabs.com/browse/PDB-2070))

* Added support relative paths to the `vardir`
  ([PDB-2271](https://tickets.puppetlabs.com/browse/PDB-2271))


#### Performance

* Removed pretty printing responses by default so that we avoid
  encoding JSON that has already been encoded by PostgreSQL. See the
  new `[developer]` configuration section to pretty print results by
  default ([PDB-2118](https://tickets.puppetlabs.com/browse/PDB-2118))

* Change edge deletion when catalog commands are processed to utilize the
  existing unique index. This significantly improves catalog command processing
  on larger installs
  ([PDB-2424](https://tickets.puppetlabs.com/browse/PDB-2424))

* Use table stats to compute number of resources which improves
  dashboard and resources per node JMX calls
  ([PDB-2335](https://tickets.puppetlabs.com/browse/PDB-2335))

* The terminus now only attempts to find the first section of data
  that can't be UTF-8 encoded when providing a debug message to the
  user ([PDB-2256](https://tickets.puppetlabs.com/browse/PDB-2256))

* We are no longer deleting unassociated report statuses which can be expensive
  on larger PuppetDB installs
  ([PDB-2321](https://tickets.puppetlabs.com/browse/PDB-2321))

* (PE Only) Added an index to support faster PuppetDB HA syncing
  ([PDB-2072](https://tickets.puppetlabs.com/browse/PDB-2072))

* Include command header information in POST params, allowing us to delay
  parsing the payload of the command
  ([PDB-2230](https://tickets.puppetlabs.com/browse/PDB-2230),
  [PDB-2159](https://tickets.puppetlabs.com/browse/PDB-2159),
  [PDB-2252](https://tickets.puppetlabs.com/browse/PDB-2252))

* Use historical-catalogs endpoint in export when available
  ([PDB-2288](https://tickets.puppetlabs.com/browse/PDB-2288))

* Use ActiveMQ to buffer benchmark command submissions, improving benchmark
  memory usage ([PDB-2277](https://tickets.puppetlabs.com/browse/PDB-2277))

* Always submit binary (UTF-8) MQ messages
  ([PDB-2441](https://tickets.puppetlabs.com/browse/PDB-2441))


### Bug fixes and maintenance

* Allow upgrades in different schemas with pg_trgm
  ([PDB-2486](https://tickets.puppetlabs.com/browse/PDB-2486))

* Upgrade to ActiveMQ 5.13.2 which fixes a bug that caused the ActiveMQ
  scheduler directory to grow indefinitely
  ([PDB-2390](https://tickets.puppetlabs.com/browse/PDB-2390))

* We no longer block startup on post migration vacuum
  ([PDB-1960](https://tickets.puppetlabs.com/browse/PDB-1960))

* Initial vacuum analyze now uses the write-db
  ([PDB-1960](https://tickets.puppetlabs.com/browse/PDB-1960))

* `group-by` no longer requires an aggregate function
  ([PDB-2262](https://tickets.puppetlabs.com/browse/PDB-2262))

* Fixed a column aliasing bug affecting certain cases of aggregate
  function/group-by application.
  ([PDB-2360](https://tickets.puppetlabs.com/browse/PDB-2360))

* We now return correct error codes for a malformed AST query, which
  previously returned as a 500
  ([PDB-2405](https://tickets.puppetlabs.com/browse/PDB-2405)

* Switch database connection pool from BoneCP (no longer maintained) to
  HikariCP ([PDB-992](https://tickets.puppetlabs.com/browse/PDB-992))

* Fixed support for the benchmark when using the archive `-A` flag
  ([PDB-2112](https://tickets.puppetlabs.com/browse/PDB-2112))

* Improved the speed of unit tests significantly by using template databases
  ([PDB-2075](https://tickets.puppetlabs.com/browse/PDB-2075))

* Merged the database migrations from PuppetDB 1.x
  ([PDB-1734](https://tickets.puppetlabs.com/browse/PDB-1734))

* Move from moustache to comidi for HTTP routing
  ([PDB-2242](https://tickets.puppetlabs.com/browse/PDB-2242))

* Upgrade to clojure version 1.8.0
  ([PDB-2331](https://tickets.puppetlabs.com/browse/PDB-2331))

* Remove dependency on data.xml
  ([PDB-2274](https://tickets.puppetlabs.com/browse/PDB-2274))

* Imports no longer require `command_versions` to be present
  ([PDB-2417](https://tickets.puppetlabs.com/browse/PDB-2417))


### Documentation updates

* Added a PQL tutorial documentation
  ([PDB-2366](https://tickets.puppetlabs.com/browse/PDB-2366))

* Added a new troubleshooting guide
  ([PDB-2436](https://tickets.puppetlabs.com/browse/PDB-2436))

* Added docs around the new historical-catalogs endpoints in PE
  ([PDB-2309](https://tickets.puppetlabs.com/browse/PDB-2309))


3.2.4
-----

PuppetDB 3.2.4 is a backward-compatible security and bugfix release that fixes a
directory permissions issue, improves the performance of certain metrics
queries, and reduces the size of anonymized export tarballs.

### Security

* Fix permissions on the PuppetDB configuration directory
  (/etc/puppetlabs/puppetdb). This directory was previously world-readable, so
  local users were able to read any PuppetDB configuration file. This includes
  the `database.ini` configuration file, which, depending on your configuration,
  may include a database password. This issue does not affect users of the
  [PuppetDB module](https://forge.puppetlabs.com/puppetlabs/puppetdb). For more
  information, see the security advisory at [https://puppetlabs.com/security/cve/puppetdb-feb-2016-advisory](https://puppetlabs.com/security/cve/puppetdb-feb-2016-advisory).

### Bug Fixes and Maintenance

* Optimize this metrics queries used by the PuppetDB dashboard. When computing
  the "percent resource duplication" and "average resources per node" metrics,
  PuppetDB now uses an approximation of the number of resources instead of an
  exact count. Additionally, the result of the "percent resource duplication"
  query is cached and recomputed at most once per minute. This greatly reduces
  query load when using the PuppetDB dashboard.
  ([PDB-2425](https://tickets.puppetlabs.com/browse/PDB-2425))

* When using the data anonymization feature of the "puppetdb export" command, we
  now anonymize text as a string of question mark characters. The previous
  behavior of using long, random strings produced data that compressed very
  poorly. Anonymized export tarballs will now be much smaller and more
  manageable.
  ([PDB-2214](https://tickets.puppetlabs.com/browse/PDB-2214))

* Improve performance of the /query/v4/nodes endpoint
  ([PDB-2324](https://tickets.puppetlabs.com/browse/PDB-2324))

* Use the read-db connection for population metrics, leveraging read replicas if
  they are available.
  ([PDB-2435](https://tickets.puppetlabs.com/browse/PDB-2435))

### Documentation

* Update paths in code examples for Puppet 4
([DOC-2560](https://tickets.puppetlabs.com/browse/DOC-2560))

### Contributors

Andrew Roetker, Garrett Guillotte, Geoff Nichols, Isaac Eldridge, Ken Barber,
Kurt Wall, Russell Mull, Ryan Senior, Sean P. McDonald, and Wyatt Alt

3.2.3
-----

PuppetDB 3.2.3 is a bugfix release that addresses an issue affecting new
installs on non-english PostgreSQL installations, as well as submission
failures that can occur on catalogs containing large amounts of binary data.

### Bug Fixes and Maintenance

* Use heap size and the content-length header to reject oversize commands on
  reception. The maximum size can be configured via `max-command-size` and
  defaults to 1/205th of heap.
  ([PDB-156](https://tickets.puppetlabs.com/browse/PDB-156))

* Use PostgreSQL's encode/decode functions (rather than trim) to make our bytea
  handling agnostic with respect to the `bytea_output` setting in PostgreSQL.
  ([PDB-2239](https://tickets.puppetlabs.com/browse/PDB-156))

* Upgrade to ActiveMQ 5.13.0.
  ([PDB-2248](https://tickets.puppetlabs.com/browse/PDB-2248))

* Only include debugging information for the first instance of non-UTF8 data
  within a command.
  ([PDB-2256](https://tickets.puppetlabs.com/browse/PDB-2256))

* Fix initial data migration for non-english PostgreSQL installations.
  ([PDB-2259](https://tickets.puppetlabs.com/browse/PDB-2259))

### Documentation

* Document session logging for failed HTTP connections.
  ([PDB-2267](https://tickets.puppetlabs.com/browse/PDB-2267))

### Contributors
Andrew Roetker, Ken Barber, Rob Browning, Rob Nelson, Russell Mull, Ryan
Senior, and Wyatt Alt

3.2.2
-----

PuppetDB 3.2.2 is a bugfix release that should improve query
performance for some fields, and changes ssl-setup to not depend on
the `puppet master` command.

### Bug fixes and maintenance

* Equality queries on some fields (some that are implemented via
  bytea) should be notably more efficient.
  ([PDB-2206](https://tickets.puppetlabs.com/browse/PDB-2206))

* The ssl-setup command will now consult `puppet agent` for the
  certname rather than `puppet master`.
  ([PDB-2100](https://tickets.puppetlabs.com/browse/PDB-2100))

* Fedora 21 has been removed from the list of supported platforms.

* The benchmark tool's -A/--archive argument has been fixed.  It was
  ignoring the archive entries.
  ([PDB-2112](https://tickets.puppetlabs.com/browse/PDB-2112))

* The instructions for building from source have been updated to
  reflect the use of [EZBake](https://github.com/puppetlabs/ezbake).
  ([PDB-2058](https://tickets.puppetlabs.com/browse/PDB-2058))

### Contributors
Andrew Roetker, Ken Barber, Morgan Haskel, Rob Braden, Russell Mull,
Ryan Senior, Wayne Warren, and Wyatt Alt.

3.2.1
-----

PuppetDB 3.2.1 was not publicly released.

3.2.0
-----

PuppetDB 3.2.0 is a backward-compatible feature release that introduces some new
API fields and parameters, better UTF-8 handling, a new experimental way of
performing subqueries, and many more enhancements and bug fixes.

### New features

* Debian 8 (Jessie) packaging support. ([PDB-1797](https://tickets.puppetlabs.com/browse/PDB-1797))

* Support for PuppetDB [query params as POST body](http://docs.puppetlabs.com/puppetdb/3.2/api/query/curl.html#querying-puppetdb-with-post),
  to allow for arbitrarily long queries. Previously, some clients and proxy servers
  were truncating or failing on large queries supplied in a GET request.
  ([PDB-1359](https://tickets.puppetlabs.com/browse/PDB-1359))

* New [`code_id`](http://docs.puppetlabs.com/puppetdb/3.2/api/query/v4/catalogs.html#query-fields)
  field in preparation for new filesync service.
  ([PDB-1810](https://tickets.puppetlabs.com/browse/PDB-1810))

* Introduction of [`latest_report_status` and `latest_report_hash`](http://docs.puppetlabs.com/puppetdb/3.2/api/query/v4/nodes.html#query-fields)
  to the `nodes` entity, as projected from the a nodes latest report.
  ([PDB-1820](https://tickets.puppetlabs.com/browse/PDB-1820))

* Experimental: [Implicit subqueries](http://docs.puppetlabs.com/puppetdb/3.2/api/query/v4/operators.html#subquery-implicit-subqueries)
  now provide a way to perform subqueries on other entities within a query
  without defining the columns to join on. This only works on relationships
  that we know up front, but should simplify most queries.
  ([PDB-1924](https://tickets.puppetlabs.com/browse/PDB-1924))

### Enhancements

* You can now disable garbage collection on a single node by setting
  [`gc-interval`](http://docs.puppetlabs.com/puppetdb/3.2/configure.html#gc-interval)
  to zero. This allows users who are running a cluster of PuppetDB instances to
  force this to run on nodes of their choosing.
  ([PDB-154](https://tickets.puppetlabs.com/browse/PDB-154))

* Improved and reduced the number of UTF-8 warnings for storage of new data.

  Prior to this work, any non-ASCII character found in a catalog would
  be replaced by /ufffd and an invalid character warning would be issued. The code now takes into account the force_encoding done by to_pson and attempts to force_encode it back to UTF-8. For most
  cases this should be sufficient.

  There are times when binary data can appear in a catalog, which creates characters that we can't represent in UTF-8. In these cases, users will still receive a warning.
  This warning now includes the command
  being submitted and the node the command is for. If users have debug
  logging enabled, context is given around where this bad character data
  occurred. Up to 100 characters before/after this bad data is included in
  the Puppet debug log. ([PDB-135](https://tickets.puppetlabs.com/browse/PDB-135))

* Support `extract` with no sub-expression. ([PDB-1459](https://tickets.puppetlabs.com/browse/PDB-1459))

* Benchmark tool now shows command rate for live feedback. ([PDB-1963](https://tickets.puppetlabs.com/browse/PDB-1963))

### Bug fixes and maintenance

* Anonymize path and value independently for structured facts. ([PDB-1614](https://tickets.puppetlabs.com/browse/PDB-1614))

* Introduce tests on benchmark tooling to ensure we don't introduce regressions. ([PDB-1627](https://tickets.puppetlabs.com/browse/PDB-1627))

* Convert anonymization tests to integration tests in Clojure. ([PDB-1698](https://tickets.puppetlabs.com/browse/PDB-1698))

* Convert to using new `java.jdbc` namespace from deprecated one for storage code. ([PDB-1738](https://tickets.puppetlabs.com/browse/PDB-1738))

* Convert to using new `java.jdbc` namespace from deprecated one for query code. ([PDB-1739](https://tickets.puppetlabs.com/browse/PDB-1739))

* Cannot parse `max-frame-size` during command processing. ([PDB-1848](https://tickets.puppetlabs.com/browse/PDB-1848))

* Created defaulted configuration service. ([PDB-1902](https://tickets.puppetlabs.com/browse/PDB-1902))

* Increase JVM PermGen maximum for JDK 7. ([PDB-1959](https://tickets.puppetlabs.com/browse/PDB-1959))

* Previously, updating 6554 or more fact values at one time produced a prepared statement
  with more parameters than can be represented with a 16-bit integer, which is the
  maximum allowed by the PostgreSQL JDBC driver. These updates will now occur in
  batches of 6000.
  ([PDB-2003](https://tickets.puppetlabs.com/browse/PDB-2003))

* Munging a catalog with :undef failed. ([PDB-2007](https://tickets.puppetlabs.com/browse/PDB-2007))

* Schema validation error on insert-facts-pv-pairs!. ([PDB-2034](https://tickets.puppetlabs.com/browse/PDB-2034))

* Stop comparing hashes for fact equality queries. ([PDB-2064](https://tickets.puppetlabs.com/browse/PDB-2064))

* Fix invalid routes, so they return a proper 404 code. ([PDB-2096](https://tickets.puppetlabs.com/browse/PDB-2096))

### Deprecations

* Deprecate `catalog-hash-conflict-debugging`. ([PDB-1932](https://tickets.puppetlabs.com/browse/PDB-1932))

* Anonymization now works on export, as opposed to being a standalone CLI tool.
  This old behaviour is now deprecated. ([PDB-1943](https://tickets.puppetlabs.com/browse/PDB-1943))

### Documentation updates

* Fix pathing for KahaDB recovery advice for new AIO pathing. ([PDB-1962](https://tickets.puppetlabs.com/browse/PDB-1962))

* Fix benchmark execution docs. ([PDB-1966](https://tickets.puppetlabs.com/browse/PDB-1966))

### Contributors

Andrew Roetker, Ken Barber, Nick Fagerlund, Rob Browing, Russell Mull,
Ryan Senior, Tim Skirvin, Wayne Warren, and Wyatt Alt.

3.1.0
-----

PuppetDB 3.1.0 is a backward-compatible feature release that includes
new aggregate operators and a new admin route that improves import/export
performance.

### Enhancements

 * Added an admin route for importing/exporting PuppetDB
   data. Previously, import/export worked by issuing GET/POST requests
   for each report/catalog/factset for a given node. This endpoint
   allows a POST or GET of a tarball containing all of the data to be
   imported or exported. This significantly improves import/export
   performance.
   ([PDB-1631](https://tickets.puppetlabs.com/browse/PDB-1631))

 * Added support for sum, average, minimum, and maximum operators to
   the PuppetDB query language
   ([PDB-1440](https://tickets.puppetlabs.com/browse/PDB-1440))

 * Added a new report wire-format that submits data to PuppetDB in a
   more resource-centric manner. Previously, resource and event data
   was munged together into a new object type before being sent to
   PuppetDB. With this change, the data is sent in a format similar to
   Puppet's internal representation of the data.

 * Added an experimental command parameter
   "secondsToWaitForCompletion" that allows command POSTs to block up
   to a specified number of seconds waiting for the command to be
   successfuly stored.

 * Added realistically spaced timestamps and transaction_uuids to
   commands generated by the benchmark tool. Previously, the same time
   and transaction_uuid was used for all submitted commands.

### Code maintenance and related changes

 * Upgrade to clojure.java.jdbc 0.3.7.
   ([PDB-1739](https://tickets.puppetlabs.com/browse/PDB-1739))

 * Switched to the Puppet version-checking library.
   ([PDB-1823](https://tickets.puppetlabs.com/browse/PDB-1823))

 * Cache database metadata on startup. Previously, code involving query
   features that were different between HyperSQL and PostgreSQL would
   require a database connection to determine the correct SQL to be
   generated. This fix changes that code to acquire relevant database metadata on
   startup and cache it.
   ([PDB-1737](https://tickets.puppetlabs.com/browse/PDB-1737))


### Contributors

  Andrew Roetker, Ken Barber, Rob Browning, Russell Mull, Ryan Senior, and Wyatt Alt.

3.0.2
-----

PuppetDB 3.0.2 is a bugfix release to address performance issues with the
aggregate-event-count and fact-paths endpoints as well as memory starvation
involving structured-facts.

### Bug fixes and maintenance

* Previously, PuppetDB cached prepared statements for SQL queries. This caused
  memory starvation involving structured-facts where large structured-facts
  would create a new prepared statement object for each update. The large
  objects would accumulate in the cache due to the non-zero cache size. This
  release addresses this issue in two ways: disabling the cache and changing
  the structured-facts update queries to be more efficient.
  ([PDB-1721](https://tickets.puppetlabs.com/browse/PDB-1721))

* Given a large number of nodes and reports, consumers of the
  aggregate-event-counts endpoint were experiencing slow response times. These
  performance issues have have been addressed in this release. One notable
  outcome from these fixes is that the endpoint no longer requires a query
  parameter.
  ([PDB-1809](https://tickets.puppetlabs.com/browse/PDB-1809))

* Some PuppetDB tables were never analyzed by the autovacuum analyzer, which led
  to performance issues with the fact-paths endpoint. This release adds code to
  perform the analyze whenever PuppetDB schema are updated.
  ([PDB-1812](https://tickets.puppetlabs.com/browse/PDB-1812))

* This release fixes an issue with the `max-frame-size` parameter, which was being
  ignored by consumers of the command queue, causing large commands to produce
  stacktraces in the PuppetDB log.
  ([PDB-1812](https://tickets.puppetlabs.com/browse/PDB-1812))

### Contributors
Andrew Roetker, Jorie Tappa, Ken Barber, Melissa Stone, Nick Fagerlund, Rob
Browning, Russell Mull, and Wyatt Alt.

3.0.1
-----

PuppetDB 3.0.1 is a bugfix release to fix packaging problems in PuppetDB 3.0.0
for RPM-based systems.

### Bug fixes and maintenance

* As part of the PuppetDB 3.0.0 release, the puppetdb-terminus package was
  renamed puppetdb-termini for consistency with other Puppet products. A
  flawed dependency in the puppetdb-termini package prevented installation of
  the older 2.3.x puppetdb-terminus package on RPM-based platforms. This release
  fixes that dependency, allowing the older version to be installed.
  ([PDB-1760](https://tickets.puppetlabs.com/browse/PDB-1760))

  To fully correct the problem, the existing 3.0.0 puppetdb and puppetdb-termini
  packages will be removed from the Puppet Collection 1 yum repository. The new
  3.0.1 packages will take their place.

* The 2.x->3.x migration script now only runs on upgrades from 2.x versions of
  PuppetDB. ([PDB-1763](https://tickets.puppetlabs.com/browse/PDB-1763))

### Contributors
Andrew Roetker, Michael Stahnke, Nick Fagerlund, and Wyatt Alt.

3.0.0
-----

PuppetDB 3.0.0 is a major release which introduces new features and some breaking changes.

### Contributors

Andrew Roetker, Andrii Nikitiuk, Brian LaMattery, Chris Price, Darin
Perusich, Eric Sorenson, Jean Bond, John Duarte, Jorie Tappa, Ken
Barber, Lars Windolf, Mathieu Parent, Matthaus Owens, Nick Fagerlund,
Rob Braden, Rob Browning, Russell Mull, Ryan Senior, Scott Garman,
Wayne Warren, and Wyatt Alt.

### Upgrading

* This version of PuppetDB adopts the AIO layout to be consistent with
  other Puppet layouts, such as Puppet Server. We recommend the
  using the [PuppetDB module][puppetdb-module] version 5, as
  it will automate much of this kind of management and has been
  modified specifically for PuppetDB 3 support by default.

  For those who don't use the PuppetDB module, this means the
  following paths have changed:

    * Primary configuration: /etc/puppetdb is now /etc/puppetlabs/puppetdb
    * Logs: /var/log/puppetdb is now /var/log/puppetlabs/puppetdb
    * Binaries: /usr/bin is now /opt/puppetlabs/bin
    * Data: /var/lib/puppetdb is now /opt/puppetlabs/server/data/puppetdb
    * Rundir: /var/run/puppetdb is now /var/run/puppetlabs/puppetdb

    During upgrade we have written a script that will migrate your configuration
    to the new locations. After upgrade you should double check the migrated
    configuration to ensure everything is correct.

* PuppetDB now only supports PostgreSQL 9.4 and newer. Support for
  versions prior to 9.4 have been retired. Users must upgrade
  PostgreSQL to 9.4 or newer before upgrading PuppetDB. Users should
  also enable the [`pg_trgm`][pg_trgm] extension, as explained
  [here][configure_postgres]. The official PostgreSQL repositories are
  recommended for packaging. See the
  [YUM](https://wiki.postgresql.org/wiki/YUM_Installation)
  instructions or the [Apt](https://wiki.postgresql.org/wiki/Apt)
  instructions for more details.

* At the moment, PuppetDB 3 only supports Puppet language version 4 or higher. We are
  investigating packaging changes necessary to support Puppet language version 3 users.
  For now you must have installed Puppet language version 4 from Puppet Collections 1
  before installing PuppetDB 3.

* Before upgrading, make sure that all of your PuppetDB instances are shut down,
  and only upgrade one at a time.

* The `puppetdb-terminus` package has now been renamed to
  `puppetdb-termini`. Make sure to upgrade your `puppetdb-terminus`
  package to the `puppetdb-termini` package (on the host where your
  Puppet master lives), and restart your master service (this is
  typically called `puppetserver`).

* Before upgrading you should ensure you have as much free disk space
  as you have data. Database schema changes (also known as migrations)
  in this release often require temporary duplication of table data
  and may mean you have to wait during the upgrade as these migrations
  run.

* After the upgrade, you may notice some additional system load for
  the first 30 to 60 minutes. This is due to internal changes in the
  way we check if objects (such as catalogs) are up to date.

* Users manually upgrading PuppetDB should restart both Puppet Server
  and PuppetDB after the upgrade. This has been automated for version
  5 users of the [PuppetDB module][puppetdb-module].

### Downgrading

* If you attempt to downgrade to a previous version of PuppetDB, in
  addition to wiping the database, you will need to clear the queue
  directory as described in the
  [documentation on KahaDB corruption][kahadb_corruption]. Otherwise
  you might see an IOException during startup when the older PuppetDB
  (using an older version of KahaDB) tries to read leftover,
  incompatible queue data.

### Breaking changes

This release retires all API versions before v4 and establishes v4 as
the stable API. The following changes have been made since the last
stable release of PuppetDB, which included an experimental v4 API.

* Versions 2 and 3 of the query API are now retired. There are also significant differences between the current stable v4 and the previously experimental v4.

    Full documentation outlining this change is available [in the API upgrade guide][upgrading].

* We've retired some previously deprecated commands. ([PDB-840](https://tickets.puppetlabs.com/browse/PDB-840))

    The currently supported commands and versions are:

    * replace catalog, v6
    * replace facts, v4
    * deactivate node, v3
    * store report, v5

    Older versions of the commands on your queue will still be
    processed by PuppetDB, so you won't lose the data from messages
    still being processed by PuppetDB before an upgrade.

* All query parameters, query responses, and command wire formats use underscores instead of dashes. ([PDB-698](https://tickets.puppetlabs.com/browse/PDB-698))

    This patch changes all incoming and outgoing JSON to use
    underscores instead of dashes, which will make it easier to
    interface with PuppetDB when using languages like JavaScript and
    Python. Query parameters also use underscores to be consistent
    with the response formats. Consult the API docs for the exact new
    parameters if you are uncertain.

* The command endpoint (`/v3/commands` or `/v4/commands`) has been split into a separate
  API, mounted at `/pdb/cmd/v1`, so it can be versioned and managed separately. ([PDB-1534](https://tickets.puppetlabs.com/browse/PDB-1534)) ([PDB-1561](https://tickets.puppetlabs.com/browse/PDB-1561))

* `/metrics` has been moved to its own service, mounted at `/metrics/v1`. ([PDB-800](https://tickets.puppetlabs.com/browse/PDB-800))

    This change will allow us to version the metrics service independently
    of the query service, and clears the way for future incorporation
    with other Puppet Labs products.

* Version and server-time are now mounted under the metadata API. ([PDB-1562](https://tickets.puppetlabs.com/browse/PDB-1562))

    These endpoints now live in `/pdb/meta/v1`.

* Change `name` key to `certname` across the API. ([PDB-1099](https://tickets.puppetlabs.com/browse/PDB-1099))

    For clarity and consistency across endpoints, the `name` key has
    been changed to `certname` where appropriate. This affects
    queries to and responses from the `/pdb/query/v4/catalogs` and `/pdb/query/v4/nodes` endpoint. The `replace catalog` and `replace facts` commands have also been changed.

* Response data for related entities (such as resources in a catalog response) is now more formalized. ([PDB-1228](https://tickets.puppetlabs.com/browse/PDB-1228))

    This change pertains to entities that are contained within other
    entities in the JSON response of our query APIs. Examples of
    these nested entities include resources and edges in catalogs or the
    new metrics and logs fields that are included in the reports
    response. In the past version of the API (including the
    experimental v4 version) this nested data was returned
    directly:

>    {
>      "certname" : "yo.delivery.puppetlabs.net",
>      "version" : "e4c339f",
>      "transaction-uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
>      "environment" : "production",
>      "edges" : [{"source": {"type": "package",n
>                             "title": "openssh-server"}
>                  "target": {"type": "file",
>                             "title": "/etc/ssh/sshd_config"}
>                  "relationship": "before"}],
>      ...
>    }

	This will now be nested with an href and the entity included as the value of the 	  	"data" key:

>    {
>      "certname" : "yo.delivery.puppetlabs.net",
>      "version" : "e4c339f",
>      "transaction-uuid" : "53b72442-3b73-11e3-94a8-1b34ef7fdc95",
>      "environment" : "production",
>      "edges" : {"href": "/pdb/query/v4/catalogs/yo.delivery.puppetlabs.net/edges",
>                 "data": [{"source": {"type": "package",
>                           "title": "openssh-server"}
>                           "target": {"type": "file",
>                           "title": "/etc/ssh/sshd_config"}
>                  "relationship": "before"}],
>      ...
>    }

	Note that HSQLDB users will not have the `data` keys pre-populated and will need to 	follow the `href` included in the response to get the  nested entity data. PostgreSQL users will always have the nested entity included.

The cause of this change is a new feature (JSON aggregation) in PostgreSQL that allows us to improve the performance of queries that contain this nested entity data. HSQLDB does not have an analogous feature, which required us to formalize how this nested entity data is returned.

For PostgreSQL consumers this means the extra `data` key needs to be traversed to access the related object data.

* Add a `noop` flag to reports. ([PDB-1177](https://tickets.puppetlabs.com/browse/PDB-1177))

    This adds a `noop` field to the reports object, which is a
    required boolean flag indicating whether the run producing the
    report used `--noop` (for example: `puppet agent -t --noop`).

* Remove `com.` prefix from JMX MBeans and the ActiveMQ destination name.  ([PDB-863](https://tickets.puppetlabs.com/browse/PDB-863))

    The `com.` prefix has been removed from the JMX MBeans and the
    ActiveMQ destination name. They are now in the
    `puppetlabs.puppetdb` namespace. Any monitoring applications you
    have that expect the old namespace will need to be changed. This
    pertains to JMX MBeans accessed via JMX and via our HTTP-based
    endpoints.

* The endpoints `aggregate-event-counts` and `event-counts` have been marked as experimental.

    These endpoints were introduced to solve specific problems for the
    Puppet Enterprise console. However, the design is under question. We're moving
    these endpoints back to experimental status so that we are able to
    deliver a better option in a future release. As per our versioning policy,
    this implies that breaking changes may be made outside of a major
    release boundary.

* The older puppetdb-\* sub-commands are now retired (`puppetdb-ssl-setup` for example) instead,
  the new sub-command style should be used `puppetdb ssl-setup`. ([PDB_663](https://tickets.puppetlabs.com/browse/PDB-663))

* Retire support for PostgreSQL versions less than 9.4. ([PDB-1592](https://tickets.puppetlabs.com/browse/PDB-1592))

* Retire support for RedHat 5, Debian 6, and Ubuntu 10.04. ([PDB-663](https://tickets.puppetlabs.com/browse/PDB-663))

* Retire `[global]` configuration key `event-query-limit`. ([PDB-693](https://tickets.puppetlabs.com/browse/PDB-693))

* Retire `[database]` configuration key `node-ttl-days`. ([PDB-644](https://tickets.puppetlabs.com/browse/PDB-644))

* Retire `[jetty]` configuration key `certificate-whitelist`. ([PDB-886](https://tickets.puppetlabs.com/browse/PDB-886))

    Moved `[jetty]` configuration key certificate-whitelist to the `[puppetdb]` section.

* PuppetDB now has a new filesystem layout that complies with the Puppet AIO layout. ([PDB-1455](https://tickets.puppetlabs.com/browse/PDB-1455))

* PuppetDB now uses the Ezbake build system, which changes the way we package. ([PDB-663](https://tickets.puppetlabs.com/browse/PDB-663))

* Due to the Ezbake changes, the `[repl]` block has been renamed to `[nrepl]` and `type` is no longer an accepted parameter. ([PDB-663](https://tickets.puppetlabs.com/browse/PDB-663))

* The endpoints contained in `/experimental` have now been removed. ([PDB-1127](https://tickets.puppetlabs.com/browse/PDB-1127))

* Return proper `404` errors for shorthand endpoints whose parents do
  not exist. Previously this returned an empty list. A shorthand
  endpoint is a convenient way to filter one entity's response using
  another entity. A good example of this kind of endpoint is using the
  nodes endpoint to get the facts associated with that node
  i.e. `/pdb/query/v4/nodes/foo.com/facts`.
  ([PDB-1473](https://tickets.puppetlabs.com/browse/PDB-1473))

* To improve performance on the `aggregate-event-counts` endpoint, we've modified the API and underlying storage indexing. We've also dropped HSQLDB support for this endpoint. ([PDB-1587](https://tickets.puppetlabs.com/browse/PDB-1587))

#### Deprecations

* HyperSQL is now deprecated and will be removed in the next major release. ([PDB-1508](https://tickets.puppetlabs.com/browse/PDB-1508))

    Installing via the package will no longer default to using
    HyperSQL. We now expect PostgreSQL by default, and will
    return an error if it hasn't been configured.

### New features

* We now store report metrics. ([PDB-1192](https://tickets.puppetlabs.com/browse/PDB-1192))

* We now store report logs. ([PDB-1192](https://tickets.puppetlabs.com/browse/PDB-1184))

* We have added `count` aggregation and `group_by` capabilities to the query API. ([PDB-1181](https://tickets.puppetlabs.com/browse/PDB-1181))

* Support `extract` as a top level query operator. ([PDB-207](https://tickets.puppetlabs.com/browse/PDB-207))

    This provides support for requesting only the fields that you need in
    an API response when querying data.

* Support fallback PuppetDB connections. ([PDB-100](https://tickets.puppetlabs.com/browse/PDB-100))

    When configuring your connection to PuppetDB, you can now specify a fallback.
    Instead of the now deprecated `server` and `port` configuration, you can use a
    `server_urls` configuration that contains the full path to one or more PuppetDB
    URLs. The old server/port format is still supported.

    This commit also supports a new `server_url_timeout` configuration. If the
    PuppetDB instance has not responded in the specified number of
    seconds (defaults to 30) it will fail and roll to the next PuppetDB
    URL (if one is provided).

* Support for hyphenated classnames, supported by Puppet 2.7.x - 3.7.x. ([PDB-1024](https://tickets.puppetlabs.com/browse/PDB-1024))

* Support for `-v` or `--version` flag to the CLI to get the PuppetDB version. ([PDB-992](https://tickets.puppetlabs.com/browse/PDB-922))

* The reports endpoint now implements the `latest_report?` query, which
  filters the response to only include reports that are from the
  latest puppet run for each node.
  ([PDB-1244](https://tickets.puppetlabs.com/browse/PDB-1244))

* Support for querying [edges](./api/query/v4/edges.html) directly. Previously edges were only returned as part of a catalog. You can also query edges specific to a particular catalog using the new `edges` suffix on the [catalogs](./api/query/v4/catalogs.html) endpoint. ([PDB-1228](https://tickets.puppetlabs.com/browse/PDB-1228)).

* Support for passing in an export archive to the load testing tool. ([PDB-1249](https://tickets.puppetlabs.com/browse/PDB-1249))

* Now the default behavior for benchmark is to use some supplied sample data, to lower the barrier for entry for the tooling. ([PDB-1249](https://tickets.puppetlabs.com/browse/PDB-1249))

* Support for querying a particular node's factset via the child endpoint. `/factsets/<node>`. ([PDB-1599](https://tickets.puppetlabs.com/browse/PDB-1599))

* Support for Apache-style access logging to PuppetDB packages, enabled by default. ([PDB-477](https://tickets.puppetlabs.com/browse/PDB-477))

    Now you can see individual requests logged by the PuppetDB application
    itself, instead of requiring a proxy to do this for you.

* Support for disabling update checking via the `[puppetdb] disable-update-checking` configuration key. ([PDB-158](https://tickets.puppetlabs.com/browse/PDB-158))

    The value defaults to false; see the [configuration](./configure.html) documentation for more details.

#### API changes

* The query parameter for `/pdb/query/v4/events` is now optional. ([PDB-1132](https://tickets.puppetlabs.com/browse/PDB-1132))

* Made `/pdb/query/v4/catalogs` queryable. ([PDB-1028](https://tickets.puppetlabs.com/browse/PDB-1028))

* Added `producer_timestamp` field to factsets and catalogs. ([PDB-489](https://tickets.puppetlabs.com/browse/PDB-489))

    On factsets the field is queryable and orderable.

* Added `producer_timestamp` field to reports. ([PDB-1487](https://tickets.puppetlabs.com/browse/PDB-1487))

* Added `resource_events` key to the `store report` command. ([PDB-1072](https://tickets.puppetlabs.com/browse/PDB-1072))

    API responses can now be easily used for resubmission as a
    report. This will be helpful in the HA context, where it will reduce
    the number of requests needed to synchronize reports.

* The command `deactivate node` now requires a `producer_timestamp` field. ([PDB-1310](https://tickets.puppetlabs.com/browse/PDB-1310))

#### Performance

* Switch from `text` based storage of UUID and hashes, to the PostgreSQL `uuid` and `bytea` types, respectively. ([PDB-1416](https://tickets.puppetlabs.com/browse/PDB-1416))

* The `reports` database table was using the hash string as its
  primary key. We have switched to using a smaller bigint primary key
  for that table. This will result in faster joins and smaller
  foreign key indexes sizes for tables relating to
  `reports`. ([PDB-1218](https://tickets.puppetlabs.com/browse/PDB-1218))

* We have dropped the `latest_reports` table in favor of storing a reference to
  the latest report of each certname in the certnames table.
  ([PDB-1254](https://tickets.puppetlabs.com/browse/PDB-1254))

### Bug fixes and maintenance

* Upgrade to Clojure 1.7.0 from 1.6.0. ([PDB-1703](https://tickets.puppetlabs.com/browse/PDB-1703))

* Improve whitelist failure message. ([PDB-1003](https://tickets.puppetlabs.com/browse/PDB-1003))

* Normalize resource event timestamps before storage. ([PDB-1241](https://tickets.puppetlabs.com/browse/PDB-1241))

* Add tags to catalog hash. ([PDB-332](https://tickets.puppetlabs.com/browse/PDB-332))

* Improve handling of invalid queries. ([PDB-950](https://tickets.puppetlabs.com/browse/PDB-950))

* Remove timestamps from factset hash calculation. ([PDB-1199](https://tickets.puppetlabs.com/browse/PDB-1199))

* Upgrade to latest Trapperkeeper. ([PDB-1095](https://tickets.puppetlabs.com/browse/PDB-1095))

* Warn user with unknown config items. ([PDB-1067](https://tickets.puppetlabs.com/browse/PDB-1067))

* We now use [`honeysql`](https://github.com/jkk/honeysql) to generate SQL. ([PDB-1228](https://tickets.puppetlabs.com/browse/PDB-1228))

* Fixed a bug in extract with two element expressions. ([PDB-1064](https://tickets.puppetlabs.com/browse/PDB-1064))

    Previously the query engine would fail if a top-level extract's
    expression had only two elements
    (i.e. `["not" ["=" "certname" "foo.com]]`). This commit adds a
    guard to the extracts used for nested queries to ensure it doesn't
    catch the two-clause version of top-level extract.

* Add validation for top-level `extract` fields.  ([PDB-1043](https://tickets.puppetlabs.com/browse/PDB-1043))

* Add validation for `in`/`extract` fields in that are passed in vector form.  ([PDB-722](https://tickets.puppetlabs.com/browse/PDB-722))

* Remove `puts` statements from PuppetDB terminus. ([PDB-1020](https://tickets.puppetlabs.com/browse/PDB-1020))

* Retire `facts.strip_internal` (terminus). ([PDB-971](https://tickets.puppetlabs.com/browse/PDB-971))

    This patch adds a `maybe_strip_internal` method to Puppet::Node::Facts::Puppetdb
    that will call `Facts#strip_internal` if the method exists, but Facts#values if
    not. This will allow our terminus to remain backward compatible when Puppet
    retires the `strip_internal` method and the `timestamp` fact.

* Allow factset hashes to be nil in PuppetDB.

    This is necessary for factsets to function after the ([PDB-898](https://tickets.puppetlabs.com/browse/PDB-898)) migration,
    because existing factsets are not being hashed.

* Fix ActiveMQ errors on PuppetDB shutdown. ([PDB-880](https://tickets.puppetlabs.com/browse/PDB-880)) ([PDB-1102](https://tickets.puppetlabs.com/browse/PDB-1102))

    We upgraded ActiveMQ to 5.7.0 (later we upgraded to 5.11.1). The
    upgrade to 5.7.0 fixed two errors that sometimes occur at
    shutdown. The first was related to the broker stopping before its
    consumers were removed. The other was related to KahaDB failing to
    reset due to the PageFile not being loaded. Upgrading fixed these
    failures.

* Refresh service configuration on package upgrades. ([PDB-917](https://tickets.puppetlabs.com/browse/PDB-917))

    Previously, upgrades were not properly refreshing the service
    information. This adds a conditional restart on upgrades.

* Update rpm spec and systemd service file for pidfile handling.

    Previously, the rpm package was creating /var/run/puppetdb at install time for
    pidfile storage. This will break on modern Fedora/RHEL and SUSE systems as
    /var/run (which is a symlink to /run) uses tmpfs and will not persist through a
    reboot.

    As the systemd service type is "simple", meaning non-forking, systemd
    manages the process directly, and the PIDfile is not needed or used on
    those platforms.

* Update metrics on PDB start. ([PDB-653](https://tickets.puppetlabs.com/browse/PDB-653))

    Previously the DLO metrics only updated on a failed command submission, which
    meant restarting PDB would blank the metrics until the next failure. This patch
    initializes DLO metrics on PDB startup if the DLO directory, which is created
    on first failure, already exists.

* Add thread names to logging. ([PDB-855](https://tickets.puppetlabs.com/browse/PDB-855))

    Add thread names to the various logback.xml configuration files
    in this project. This provides us with better traceability when attempting
    to understand where a log message came from.

* Add better validation to benchmark tool for `--runinterval` and `--numhosts`. ([PDB-1268](https://tickets.puppetlabs.com/browse/PDB-1268))

* Upgrade `clj-time`, `compojure` and `ring-core` to later revisions. ([PDB-1460](https://tickets.puppetlabs.com/browse/PDB-1460))

* Update `me.raynes/fs` to 1.4.5. ([PDB-1606](https://tickets.puppetlabs.com/browse/PDB-1606))

* Improve the error message returned for the `distinct_resources` parameter on the `aggregate-event-counts` endpoint. ([PDB-1391](https://tickets.puppetlabs.com/browse/PDB-1391))

* Refactored http streaming code. ([PDB-1465](https://tickets.puppetlabs.com/browse/PDB-1465))

* Reject comparison queries with non-numerics on the `/facts` endpoint. ([PDB-1453](https://tickets.puppetlabs.com/browse/PDB-1453))

* Consider reports as part of node expiration, and don't expire nodes that have had a report since the currently configured `node-ttl`. ([PDB-1638](https://tickets.puppetlabs.com/browse/PDB-1638))

* Compute and store a hash of the factset for `replace facts` commands.  ([PDB-898](https://tickets.puppetlabs.com/browse/PDB-898))

    This commit associates a hash of each factset with the metadata of the factset
    to allow us to easily tell if fact values have changed.

* Remove clamq wrapper for handling ActiveMQ internally, replacing it with our own. ([PDB-1252](https://tickets.puppetlabs.com/browse/PDB-1252))

* Shutdown connection pooling properly when shutting down the service. ([PDB-1348](https://tickets.puppetlabs.com/browse/PDB-1348))

* Garbage collection routines now use `producer_timestamp` for determining deactivation status. ([PDB-1289](https://tickets.puppetlabs.com/browse/PDB-1289))

* Added pagination to service query method. ([PDB-1071](https://tickets.puppetlabs.com/browse/PDB-1071))

    Add a `paging_options` argument to the service query method.

### Documentation updates

* Add documentation on terminus failover support. ([PDB-486](https://tickets.puppetlabs.com/browse/PDB-486))

* Document the web-router-service. ([PDB-1070](https://tickets.puppetlabs.com/browse/PDB-1070))

* Update operators doc link for PostgreSQL 9.3. ([PDB-775](https://tickets.puppetlabs.com/browse/PDB-775))

* Add docs for new CRL and cert-chain TK features. ([PDB-347](https://tickets.puppetlabs.com/browse/PDB-347))

* Fix the `replace-facts` commands API examples. ([PDB-859](https://tickets.puppetlabs.com/browse/PDB-859))

* Include the navigation sidebar inside the `puppetdb` repo. ([PDB-1319](https://tickets.puppetlabs.com/browse/PDB-1319))


2.3.8
-----

PuppetDB 2.3.8 is a backward-compatible bugfix release to address a garbage
collection issue that can arise when a node changes 6554 or more fact values
in a single run, or when prepending to array-valued structured facts with
leaves totaling 6554 or more.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres]. We have
  officially deprecated versions prior to 9.4.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

Ken Barber, Ryan Senior, Wyatt Alt

### Bug fixes and maintenance

* Updating 6554 or more fact values at one time would produce a prepared statement
  with more parameters than can be represented with a 16-bit integer, which is the
  maximum allowed by the PostgreSQL JDBC driver. These updates will now occur in
  batches of 6000, so the limit should not be reached.
  ([PDB-2003](https://tickets.puppetlabs.com/browse/PDB-2003))

* Minor changes to testing and documentation updates.

2.3.7
-----

PuppetDB 2.3.7 is a backwards-compatible bugfix release to resolve
an issue with ActiveMQ configuration.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres]. We have
  officially deprecated versions prior to 9.4.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

Andrew Roetker, Russell Mull

### Bug fixes and maintenance

* The max-frame-size setting is now applied to the ActiveMQ consumer thread. It
  was previously set to the default, which could case errors when extremely
  large commands were submitted.
  ([PDB-700](https://tickets.puppetlabs.com/browse/PDB-700))

2.3.6
-----

PuppetDB 2.3.6 is a backwards-compatible bugfix release to introduce
deprecation warnings for PostgreSQL versions less than 9.4. It also fixes a bug
in the ssl-setup script that would complicate downgrades from 3.0, and a bug
where the PuppetDB terminus would reject resource relationships on aliases.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres]. We have
  officially deprecated versions prior to 9.4.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

Andrew Roetker, Ken Barber, Nick Fagerlund, Rob Browning, Russell Mull, Wyatt
Alt

#### Bug fixes and maintenance

* We have removed some checks for AIO pathing in our ssl-setup script, which
  could prevent the tool from running in the case of a 3.0 downgrade.
  ([PDB-1679](https://tickets.puppetlabs.com/browse/PDB-1679))

* We have fixed a bug that would cause the PuppetDB terminus to reject catalogs
  with relationships on resource aliases.
  ([PDB-1629](https://tickets.puppetlabs.com/browse/PDB-1629))

#### Deprecations

* We have deprecated versions of PostgreSQL prior to 9.4.
([PDB-1610](https://tickets.puppetlabs.com/browse/PDB-1610))

#### Testing

* We have updated our acceptance tests to use the newly released v5
puppetlabs-puppetdb module.
([PDB-1750](https://tickets.puppetlabs.com/browse/PDB-1750))

2.3.5
-----

PuppetDB 2.3.5 is a backwards-compatible bugfix release primarily geared toward
extending support for the all in one agent.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres], and we
  have officially deprecated versions earlier than 9.2.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

John Duarte, Ken Barber, Matthaus Owens, Melissa Stone, Rob Browning, Russell
Mull, Ryan Senior, Wyatt Alt

### Changes

#### Bug fixes and maintenance

* We have fixed a bug that caused PuppetDB to reject resource relationship
  titles containing newlines. ([PDB-1529](https://tickets.puppetlabs.com/browse/PDB-1529))

* We have removed an extraneous dependency on rubygem-json, which was
  preventing the PuppetDB terminus from installing with the all in one agent on RHEL 6.
  ([PDB-1469](https://tickets.puppetlabs.com/browse/PDB-1469))

* We have fixed a bug that caused unneccessary contention on the certnames
  table during command submission, which in turn caused commands to be retried
  more often than necessary.
  ([PDB-1550](https://tickets.puppetlabs.com/browse/PDB-1550))

* We have added additional error handling around PuppetDB startup failures that
  can be caused by server downgrades that implicitly downgrade ActiveMQ.
  ([PDB-1454](https://tickets.puppetlabs.com/browse/PDB-1454))

#### Testing

* We have updated our acceptance tests to run against Puppet 4 with AIO in
  addition to Puppet 3.
  ([PDB-1300](https://tickets.puppetlabs.com/browse/PDB-1300))

2.3.4
-----

PuppetDB 2.3.4 is a backwards-compatible bugfix release that addresses an issue
that would prevent fact storage for nodes under certain circumstances.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres], and we
  have officially deprecated versions earlier than 9.2.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

Andrew Roetker, Deepak Giridharagopal, Ken Barber, Melissa Stone, Rob Browning,
Wyatt Alt

### Changes

#### Bug fixes and maintenance

* We have fixed a bug where if any of the facts for a given node exchange values
within a single puppet run, and none of values are referred to by another node,
the facts update for the node would fail. ([PDB-1448](https://tickets.puppetlabs.com/browse/PDB-1448))

* We have fixed a streaming issue with our facts response, so facts queries
  will not consume as much memory. ([PDB-1470](https://tickets.puppetlabs.com/browse/PDB-1470))

* We no longer log failures to connect to the puppetlabs update service at the
  info level. ([PDB-1428](https://tickets.puppetlabs.com/browse/PDB-1428))

* We have removed Ubuntu 10.04 and Fedora 19 from our build targets as they are
  end of life and no longer supported by Puppet Labs. ([PDB-1449](https://tickets.puppetlabs.com/browse/PDB-1449))

* We have upgraded to the latest version of prismatic/schema.
  ([PDB-1432](https://tickets.puppetlabs.com/browse/PDB-1432))

* We have upgraded to the latest versions of and trapperkeeper-jetty9-webserver. ([PDB-1439](https://tickets.puppetlabs.com/browse/PDB-1439))

#### Documentation

* We have documented the upgrade process from the v3 API to the v4 API [here][upgrading]. ([PDB-1421](https://tickets.puppetlabs.com/browse/PDB-1421))

#### Deprecations

* We have marked the `url-prefix` setting as deprecated and will remove it in a
  future release. ([PDB-1485](https://tickets.puppetlabs.com/browse/PDB-1485))

* We have marked the event-counts and aggregate-event-counts endpoints as experimental,
  and may change or remove them in a future release.
  ([PDB-1479](https://tickets.puppetlabs.com/browse/PDB-1479))

2.3.3
-----

PuppetDB 2.3.3 is a backwards-compatible bugfix release that adds
support for Puppet 4 on Debian and Ubuntu platforms.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres], and we
  have officially deprecated versions earlier than 9.2.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

Matthaus Owens, Rob Browning

### Changes

#### Bug fixes and maintenance

* PuppetDB now supports Puppet 4 on Debian and Ubuntu.
  ([PDB-1389](https://tickets.puppetlabs.com/browse/PDB-1389))

2.3.2
-----

PuppetDB 2.3.2 is a backwards-compatible bugfix release that corrects
two problems which could prevent migration to the database format
introduced in 2.3.1.  Neither problem presented risk of data loss, and
users that upgraded successfully will not be affected by these
changes.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres], and we
  have officially deprecated versions earlier than 9.2.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

Rob Browning

### Changes

#### Bug fixes and maintenance

* The database migration required by the garbage collection fix
  in 2.3.1 should no longer refuse to complete when the existing
  database contains values with different types, but the same path, in
  different factsets, i.e. (factset path value):

      1 "pip_version" false
      2 "pip_version" "1.5.6-5"

  ([PDB-1362](https://tickets.puppetlabs.com/browse/PDB-1362))

* PuppetDB won't assume that it can retrieve the public database table
  names (initially assumed in 2.3.1).
  ([PDB-1363](https://tickets.puppetlabs.com/browse/PDB-1363))

2.3.1
-----

PuppetDB 2.3.1 is a backwards-compatible bugfix release that may
notably increase performance for larger installations.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres], and we
  have officially deprecated versions earlier than 9.2.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

John Duarte, Ken Barber, Matthaus Owens, Nick Fagerlund, Russell Mull,
Wyatt Alt, Rob Browning, Ryan Senior, juniorsysadmin

### Changes

* The tk-jetty9-version has been upgraded from 1.2.0 to 1.3.0.
  ([PDB-1307](https://tickets.puppetlabs.com/browse/PDB-1307))

#### Bug fixes and maintenance

* We have reworked out table schema and fact value garbage
  collection to ensure that concurrent updates cannot lead
  to fact GC failures.  Before this fix PuppetDB, under high load,
  could end up in a situation which would produce PostgreSQL log
  messages like this:

    ERROR:  update or delete on table "fact_paths" violates foreign key constraint "fact_values_path_id_fk" on table "fact_values"
    DETAIL:  Key (id)=(11) is still referenced from table "fact_values".
    STATEMENT:  COMMIT

  The fix for this problem may also significantly increase PuppetDB's
  performance in some situations.
  ([PDB-1031](https://tickets.puppetlabs.com/browse/PDB-1031)
   [PDB-1224](https://tickets.puppetlabs.com/browse/PDB-1224)
   [PDB-1225](https://tickets.puppetlabs.com/browse/PDB-1225)
   [PDB-1226](https://tickets.puppetlabs.com/browse/PDB-1226)
   [PDB-1227](https://tickets.puppetlabs.com/browse/PDB-1227))

* Report timestamps should be hashed consistently now.
  ([PDB-1286](https://tickets.puppetlabs.com/browse/PDB-1286))

* The facter requirement has been removed from the RPM specfile.
  ([PDB-1303](https://tickets.puppetlabs.com/browse/PDB-1303))

#### Documentation

* The documentation has been updated for Puppet 4.
  ([PDB-1305](https://tickets.puppetlabs.com/browse/PDB-1305))

* Omitted .html extensions have been added to some the links in the
  2.3.0 release notes.

* The documentation sidebar (TOC) is now hosted in the PuppetDB
  repository. ([PDB-1319](https://tickets.puppetlabs.com/browse/PDB-1319))

2.3.0
-----

PuppetDB 2.3.0 is a backwards-compatible release that adds support for
Puppet 4.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres], and we
  have officially deprecated versions earlier than 9.2.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet master lives), and restart your master service.

### Contributors

Andrew Roetker, Erik Dalén, Jean Bond, Ken Barber, Preben Ingvaldsen,
Rob Braden, Rob Browning, Rob Nelson, Roger Ignazio, Russell Mull,
Ryan Senior, and Wyatt Alt

### Changes

#### New features

* PuppetDB now supports Puppet 4

* To support Puppet 2.7.x - 3.7.x. PuppetDB now handles hyphenated
  classnames
  ([PDB-1024](https://tickets.puppetlabs.com/browse/PDB-1024)
  [PDB-1277](https://tickets.puppetlabs.com/browse/PDB-1277))

#### Deprecations

* The `certificate-whitelist` setting has been moved from `[jetty]` to
  the `[puppetdb]` section of the configuration file, and the old
  location has been deprecated.
  ([PDB-813](https://tickets.puppetlabs.com/browse/PDB-813))

#### Bug fixes and maintenance

* NULL environment_ids should no longer prevent garbage collection.
  ([PDB-1076](https://tickets.puppetlabs.com/browse/PDB-1076))

    If a factset, report, or catalog is migrated from an earlier
    PuppetDB version or is submitted via an earlier puppetdb-terminus
    version, it will have NULL for the environment_id.  Previously
    that could prevent PuppetDB from cleaning up any environments at
    all.  The fix should also improve collection performance.

* The puppet-env script should no longer contain invalid code.
  Previously commands could end up on the same line, preventing (for
  example) environment variables from being set
  correctly. ([PDB-1212](https://tickets.puppetlabs.com/browse/PDB-1212))

* PuppetDB now uses the new Puppet Profiler API.
  ([PUP-3512](https://tickets.puppetlabs.com/browse/PUP-3512))

* `Profiler.profile()` should now be called with the correct arguments
  across all supported versions.
  ([PDB-1029](https://tickets.puppetlabs.com/browse/PDB-1029))

* PuppetDB has migrated to a newer version of Trapperkeeper which
  among other things, should help alleviate some startup failures on
  systems with larger CPU core counts.
  ([PDB-1247](https://tickets.puppetlabs.com/browse/PDB-1247))

* The factset endpoint should no longer accept some unused and
  unintentionally accepted arguments.

#### Documentation

* The documentation for the `<=` and `>=`
  [operators](http://docs.puppetlabs.com/puppetdb/2.3/api/query/v2/operators.html) has been fixed (the
  descriptions were incorrectly reversed).

* The firewall and SELinux requirements have been documented
  [here](http://docs.puppetlabs.com/puppetdb/2.3/connect_puppet_master.html).
  ([PDB-137](https://tickets.puppetlabs.com/browse/PDB-137))

* Broken links have been fixed in the
  [connection](http://docs.puppetlabs.com/puppetdb/2.3/connect_puppet_master.html) and [commands](./api/command/v1/commands.html)
  documentation.

* A missing `-L` option has been added to a curl invocation
  [here](http://docs.puppetlabs.com/puppetdb/2.3/install_from_source.html).

* An incorrect reference to "Java" has been changed to "JVM" in the
  [configuration](http://docs.puppetlabs.com/puppetdb/2.3/configure.html) documentation.

* The relationship between "MQ depth" and "Command Queue depth" has
  been clarified in the [tuning and maintenance](http://docs.puppetlabs.com/puppetdb/2.3/maintain_and_tune.html)
  documentation.

* An example that uses curl with SSL to communicate with Puppet
  Enterprise has been added to the [curl](http://docs.puppetlabs.com/puppetdb/2.3/api/query/curl.html)
  documentation.

* Some minor edits have been made to the
  [fact-contents](http://docs.puppetlabs.com/puppetdb/2.3/api/query/v4/fact-contents.html),
  [connection](http://docs.puppetlabs.com/puppetdb/2.3/connect_puppet_master.html), and
  [KahaDB Corruption](http://docs.puppetlabs.com/puppetdb/2.3/trouble_kahadb_corruption.html) documentation.

#### Testing

* The tests have been adjusted to accommodate Puppet 4.
  ([PDB-1052](https://tickets.puppetlabs.com/browse/PDB-1052)
  [PDB-995](https://tickets.puppetlabs.com/browse/PDB-995)
  [PDB-1271](https://tickets.puppetlabs.com/browse/PDB-1271)
  [68bf176e0bd4d51c1ba3](https://github.com/puppetlabs/puppetdb/commit/68bf176e0bd4d51c1ba36909f4966671379a775e)
  [9ef68b635d1bb1f5338b](https://github.com/puppetlabs/puppetdb/commit/9ef68b635d1bb1f5338b3e40034a2ed787f2e107))

* The memory limit has been raised to 4GB for Travis CI tests.
  ([PDB-1202](https://tickets.puppetlabs.com/browse/PDB-1202))

* For now, the Fedora acceptance tests pin Puppet to 3.7.3.
  ([PDB-1200](https://tickets.puppetlabs.com/browse/PDB-1200))

* The Puppet version used by the spec tests can now be specified.
  ([PDB-1012](https://tickets.puppetlabs.com/browse/PDB-1012))

    The desired puppet version can be selected by setting the
    puppet_branch environment variable.  Values of `latest` and
    `oldest` will select the latest and oldest supported versions of
    puppet respectively.

* The bundler `--retry` argument is now used during acceptance
  testing.

* Retriable has been pinned to version `~> 1.4` to avoid Ruby 1.9.x
  incompatible versions.

* The tree-generator should be less likely to generate inappropriate test data.
  ([PDB-1109](https://tickets.puppetlabs.com/browse/PDB-1109))

* The source of the Leiningen command has been updated.

* The ec2 acceptance test template's el-5 image has been updated.
  ([7cd01ac051c719c6c768](https://github.com/puppetlabs/puppetdb/commit/68bf176e0bd4d51c1ba36909f4966671379a775e))

* Some v4 node test failures should no longer be hidden.
  ([PDB-1017](https://tickets.puppetlabs.com/browse/PDB-1017))

* Pull request testing now invokes a script from the repository
  instead of the Jenkins job.
  ([PDB-1034](https://tickets.puppetlabs.com/browse/PDB-1034))

* The Gemfile pins i18n to `~> 0.6.11` for Ruby 1.8.7 to prevent
  activesupport from pulling in a version of i18n that isn't
  compatible with Ruby 1.8.7.

* The acceptance tests now use Virtual Private Cloud (VPC) hosts.

* The Beaker AWS department (`BEAKER_department`) is now set to
  `eso-dept` for the acceptance tests to improve AWS usage reporting.

* The legacy certificate whitelist test has been fixed.
  ([PDB-1247](https://tickets.puppetlabs.com/browse/PDB-1247))

2.2.2
-----

PuppetDB 2.2.2 is a backwards-compatible security release to update our default
ssl settings and tests in response to the POODLE SSLv3 vulnerability disclosed 10/14/2014.

(see http://web.nvd.nist.gov/view/vuln/detail?vulnId=CVE-2014-3566)

### Before Upgrading

* For best-possible performance and scaling capacity, we recommend using the latest version of PostgreSQL (9.3 or higher).
We have officially deprecated PostgreSQL 9.1 and below. If you are using HSQLDB for production,
we also recommended switching to PostgreSQL at least 9.3, as HSQLDB has a number of scaling
and operational issues and is only recommended for testing and proof of concept installations.

* For PostgreSQL 9.3 we advise that users install the PostgreSQL extension `pg_trgm` for increased
indexing performance for regular expression queries. Using the command `create extension pg_trgm`
as PostgreSQL super-user and before starting PuppetDB will allow these new indexes to be created.

* Ensure during a package upgrade that you analyze any changed configuration files. For Debian
you will receive warnings when upgrading interactively about these files, and for RedHat based
distributions you will find that the RPM drops .rpmnew files that you should diff and ensure
that any new content is merged into your existing configuration.

* Make sure all your PuppetDB instances are shut down and only upgrade one at a time.

* As usual, don't forget to also upgrade your puppetdb-terminus package
(on the host where your Puppet master lives), and restart your
master service.

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

### Contributors

Ken Barber, Ryan Senior

#### Security
* [PDB-962](https://tickets.puppetlabs.com/browse/PDB-962)

    This commit changes the default ssl protocol in the Jetty config from SSLv3 to TLSv1.
    If the user has specified SSLv3, this is allowed, but the user will be warned.

#### Testing
* [PDB-964](https://tickets.puppetlabs.com/browse/PDB-964)

    Change tests to use TLSv1 to avoid dependency issues on sites dropping TLSv1.

* [PDB-952](https://tickets.puppetlabs.com/browse/PDB-952)

     Add acceptance tests for CentOS 7

#### Documentation

* Update docs to include --tlsv1 in all https curl examples

2.2.1
-----

PuppetDB 2.2.1 consists of bug fixes and documentation updates and is
backwards compatible with PuppetDB 2.2.0.

### Before upgrading

* For best-possible performance and scaling capacity, we recommend using the latest version of PostgreSQL (9.3 or higher).
We have officially deprecated PostgreSQL 9.1 and below. If you are using HSQLDB for production,
we also recommended switching to PostgreSQL at least 9.3, as HSQLDB has a number of scaling
and operational issues and is only recommended for testing and proof of concept installations.

* For PostgreSQL 9.3 we advise that users install the PostgreSQL extension `pg_trgm` for increased
indexing performance for regular expression queries. Using the command `create extension pg_trgm`
as PostgreSQL super-user and before starting PuppetDB will allow these new indexes to be created.

* Ensure during a package upgrade that you analyze any changed configuration files. For Debian
you will receive warnings when upgrading interactively about these files, and for RedHat based
distributions you will find that the RPM drops .rpmnew files that you should diff and ensure
that any new content is merged into your existing configuration.

* Make sure all your PuppetDB instances are shut down and only upgrade one at a time.

* As usual, don't forget to also upgrade your puppetdb-terminus package
(on the host where your Puppet master lives), and restart your
master service.

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

### Contributors

Justin Holguin, Ken Barber, Kylo Ginsberg, Russell Sim, Ryan Senior and Wyatt Alt.

### Database and performance

* [PDB-900](https://tickets.puppetlabs.com/browse/PDB-900) Make performance improvements to fact values GC process

    Switched from a background thread to cleanup orphaned fact_values to one
    that deletes as it goes. Switching to deferred constraints also
    significantly improved performance on PostgreSQL. Deferred constraints are
    not available in HSQLDB, but because HSQLDB is unlikely to be running at the
    scale that will surface the issue, simply switching to delete-as-you-go
    should be sufficient to mitigate the issue to the extent that HSQL
    installations are affected.

### Bug fixes and maintenance

* [PDB-847](https://tickets.puppetlabs.com/browse/PDB-847) remove GlobalCheck for PE compatibility

    This patch deletes the GlobalCheck method that ran before each call to
    the PDB indirectors to check that the running version of Puppet was greater
    than 3.5. The check is redundant because Puppet 3.5 is a requirement for
    puppetdb-terminus and was causing a bug by mis-parsing PE semvers.

* [PDB-707](https://tickets.puppetlabs.com/browse/PDB-707) The first request to PuppetDB after DB backend restart fails

    This patch adds retry handling for 57P01 failures, which result in an
    PSQLException with a status code of 08003. The patch has been made
    forward compatible by catching all SQLExceptions with this status code.

    Version 0.8.0-RELEASE of Bonecp does not throw the correct sql state code
    today, however a patch has been raised to make this happen so we can
    upgrade to this version in the future.

* [PDB-653](https://tickets.puppetlabs.com/browse/PDB-653) DLO metrics don't update on PDB startup

    Previously, the DLO metrics only updated on a failed command submission, which
    meant restarting PDB would blank the metrics until the next failure. This patch
    initializes DLO metrics on PDB startup if the DLO directory, which is created
    on first failure, already exists.

* [PDB-865](https://tickets.puppetlabs.com/browse/PDB-865) PDB 2.2 migration fails when nodes have no facts

    This patch handles an error that occurs if a user attempts the 2.2 upgrade
    with nodes that have no associated facts in the database, and adds some
    additional testing around the migration.

* [PDB-868](https://tickets.puppetlabs.com/browse/PDB-868) Add exponential backoffs for db retry logic

    This patch increases the number of retries and adds exponential backoff
    logic to avoid the case where the database is still throwing errors for a short
    period of time. This commonly occurs during database restarts.

* [PDB-905](https://tickets.puppetlabs.com/browse/PDB-905) Fix containment-path for skipped events

    We had removed some < v4 reporting code as part of the last release, but I had
    also accidentally removed containment-path for skipped events. This was an
    effort to drop older Puppet support basically.

    This patch fixes that problem, and fixes the tests.

    I've also cleaned up a couple of other typos and style items, plus
    removed one more case of < v4 report format checking.

* [PDB-904](https://tickets.puppetlabs.com/browse/PDB-904) Switch fact_values.value_string trgm index to be GIN not GIST

    There were reports of crashing due to a bug in pg_trgm indexing when a
    large fact value was loaded into PuppetDB.

    This patch removes the old index via migration, and will then replace it
    with the index handling mechanism.

* Add thread names to logging

    This patch adds thread names to the various logback.xml configuration files
    in this project. This provides us with better traceability when attempting
    to understand where a log message came from.

### Documentation

* [DOCUMENT-18](https://tickets.puppetlabs.com/browse/DOCUMENT-18) Mention DLO cleanup in docs

* [PDB-347](https://tickets.puppetlabs.com/browse/PDB-347) Add docs for new CRL and cert-chain TK features

* Updated contributor link to https://cla.puppetlabs.com/

### Testing

* [PDB-13](https://tickets.puppetlabs.com/browse/PDB-13) Add acceptance test for Debian Wheezy

    This patch adds Debian Wheezy to our acceptance tests.

* Allow beaker rake task to accept preserve-hosts options

    In the past it just allowed true/false, but the current beaker accepts a series
    of options, including 'onfail'. This change passes through the value specified
    in the environment variable BEAKER_PRESERVE_HOSTS.

* Remove deprecated open_postgres_port option from puppetdb helper class

2.2.0
-----

This release was primarily focused on providing structured facts support for PuppetDB. Structured facts
allow a user to include hashes and arrays in their fact data, but also it provides the availability of
proper typing to include the storage of integers, floats, booleans as well as strings.

This release introduces the ability to store structured facts in PuppetDB, and use some new enhanced API's
to search and retrieve that data also.

With this change we have introduced the capability to store and retrieve trusted facts, which
are stored and retrieved in the same way as structured facts.

### Before upgrading

* It is recommended for greater scaling capabilities and performance, to use the latest version of PostgreSQL (9.3 or higher).
We have officially deprecated PostgreSQL 9.1 and below. If you are using HSQLDB for production,
it is also recommended that you switch to PostgreSQL at least 9.3, as HSQLDB has a number of scaling
and operational issues, and is only recommended for testing and proof of concept installations.

* For PostgreSQL 9.3 you are advised to install the PostgreSQL extension `pg_trgm` for increased
indexing performance for regular expression queries. Using the command `create extension pg_trgm`
as PostgreSQL super-user and before starting PuppetDB will allow these new indexes to be created.

* Ensure during a package upgrade that you analyze any changed configuration files. For Debian
you will receive warnings when upgrading interactively about these files, and for RedHat based
distributions you will find that the RPM drops .rpmnew files that you should diff and ensure
that any new content is merged into your existing configuration.

* Make sure all your PuppetDB instances are shut down and only upgrade one at a time.

* As usual, don't forget to upgrade your puppetdb-terminus package
also (on the host where your Puppet master lives), and restart your
master service.

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 then you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs, and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

### Contributors

Brian Cain, Eric Timmerman, Justin Holguin, Ken Barber, Nick Fagerlund, Ryan Senior and Wyatt Alt.

### New features

#### New endpoints

* [/v4/fact-contents](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/fact-contents.html)

    This end-point provides a new view on fact data, with structure in mind. It provides a way to
    traverse a structured facts paths and their values to search for and retrieve data contained deep within hashes,
    arrays and combinations there-of.

* [/v4/fact-paths](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/fact-paths.html)

    To aid with client application autocompletion and ahead-of-time fact schema layout this endpoint
    will provide the client with a full list of potential fact paths and their value types. This data
    is primarily used by user agents that wish to perhaps confine input via a form, or provide some
    level of autocompletion advice for a user typing a fact search.

* [/v4/factsets](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/factsets.html)

    This endpoint has been created to facilitate retrieval of factsets submitted
    for a node. With structured facts support, this includes preserving the type of the fact
    which may include a hash, array, real, integer, boolean or string.

    We have now switched to using this endpoint for the purposes of `puppetdb export`.
    This allows us to grab a whole node factsets in one go, in the past we would have to reassemble multiple top
    level facts to achieve the same results.

#### Changes to endpoints

* [/v4/facts](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/facts.html)

    This endpoint is now capable of returning structured facts. Facts that contain hashes, arrays, floats, integers,
    booleans, strings (and combinations thereof) will be preserved when stored and able to now be returned via this
    endpoint.

* [/v3/facts](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v3/facts.html)

    This endpoint will return JSON stringified structured facts to preserve backwards compatibility.

#### Operators

* [`in` and `extract` (version 4)](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/operators.html#in)

    We have modified the v4 IN and EXTRACT operators to accept multiple fields at one time.
    This allows the following...

        ["and" ["in", "name",
                 ["extract", "name", [select-fact-contents ["=","value",10]]]]
               ["in", "certname",
                 ["extract", "certname", [select-fact-contents ["=", "value", 10]]]]]

    to be re-written as

        ["in", ["name","certname"],
          ["extract", ["name", "certname"], ["select-fact-contents", ["=", "value", 10]]]]

    This was made to allow users to combine the `fact-contents` endpoint with the `facts` endpoint to combine the power
    of hierachical searching and aggregate results.

* [`~>` (version 4)](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/operators.html#regexp-array-match)

    This new operator was designed for the `path` field type to allow for matching a path
    against an array of regular expressions. The only endpoints that contains such fields today
    are [/v4/fact-contents](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/fact-contents.html) and [/v4/fact-paths](http://docs.puppetlabs.com/puppetdb/2.2/api/query/v4/fact-paths.html).

#### Commands

In preparation for some future work, we have provided the ability to pass a `producer-timestamp` field via the `replace facts` and `replace catalogs` commands.
For `replace facts` we have also added the ability to pass any JSON object as the keys to the `value` field.

Due to these two changes, we have cut new versions of these commands for this release:

* [`replace catalog` version 5](http://docs.puppetlabs.com/puppetdb/2.2/api/wire_format/catalog_format_v5.html)
* [`replace facts` version 3](http://docs.puppetlabs.com/puppetdb/2.2/api/wire_format/facts_format_v3.html)

The older versions of the commands are immediately deprecated.

#### Terminus

The terminus changes for structured facts are quite simple, but significant:

* We no longer convert structured data to strings before submission to PuppetDB.
* Trusted facts have now been merged into facts in our terminus under the key `trusted`.

This means that for structured and trusted fact support all you have to do is enable them in Puppet,
and PuppetDB will start storing them immediately.

#### Database and performance

* As part of the work for PDB-763 we have added a new facility to nag users to install the `pg_trgm` extension so we
  can utilize this index type for regular expression querying. This work is new, and only works on PostgreSQL 9.3 or higher.

* We have added two new garbage collection SQL cleanup queries to remove stale entries for structured facts.

#### Import/export/anonymization

All these tools have been modified to support structured facts. `export` specifically uses the new `/v4/factsets` endpoint now.

### Deprecations and retirements

* We have now deprecated PostgreSQL 9.1 and older
* We no longer produce packages for Ubuntu 13.10 (it went EOL on July 17 2014)

### Bug fixes and maintenance

* [PDB-762](https://tickets.puppetlabs.com/browse/PDB-762) Fix broken export

    This pull request fixes a malformed post-assertion in the events-for-report-hash
    function that caused exports to fail on unchanged reports. Inserting proper
    parentheses made it apparent that a seq, rather than a vector, should be the
    expected return type.

* [PDB-826](https://tickets.puppetlabs.com/browse/PDB-826) Fix pathing for puppetdb-legacy

    For PE the puppetdb-legacy scripts (such as puppetdb-ssl-setup) expect the
    puppetdb script to be in the path. This assumption isn't always true, as we
    install PE in a weird place.

    This patch adjusts the PATH for this single exec so that it also includes
    the path of the directory where the original script is found, which is usually
    /opt/puppet/sbin.(PDB-826) Fix pathing for puppetdb-legacy

* [PDB-764](https://tickets.puppetlabs.com/browse/PDB-764) Malformed post conditions in query.clj.

    This fixes some trivial typos in query.clj that had previously caused some
    post-conditions to go unenforced.

### Documentation

* [DOCUMENT-97](https://tickets.puppetlabs.com/browse/DOCUMENT-97) Mention updating puppetdb module

    Upgrading PuppetDB using the module is pretty easy, but we should point
    out that the module should be updated *first*.

* [PDB-550](https://tickets.puppetlabs.com/browse/PDB-550) Update PuppetDB docs to include info on [LibPQFactory](./postgres_ssl.html)

    We have now updated our PostgreSQL SSL connectivity to documentation to include
    details on how to use the LibPQFactory methodology. This hopefully will alleviate
    the need to modify your global JVM JKS when using Puppet and self-signed certificates.

* Fix example for nodes endpoint to show 'certname' in response

* Revise API docs for updated info, clarity, consistency, and formatting

    This revision touches most of the pages in the v3 and v4 API docs, as well as
    the release notes. We've:

    * Standardized some squirmy terminology
    * Adjusted the flow of several pages
    * Caught two or three spots where the docs lagged behind the implementation
    * Made the Markdown syntax a little more portable (summary: Let's not use the
      "\n: " definition list syntax anymore. Multi-graf list items and nested lists
      get indented four spaces, not two or three.)
    * Added context about how certain objects work and how they relate to other objects
    * Added info about how query operators interact with field data types

* Change some old URLs, remove mentions of inventory service.

    Some of these files have moved, and others should point to the latest version
    instead of a specific version.

    And the inventory service is not really good news anymore. People should just
    use puppetdb's api directly.

### Testing

* [PDB-822](https://tickets.puppetlabs.com/browse/PDB-822) Change acceptance tests to incorporate structured facts.

    This patch augments our basic_fact_retrieval and import_export acceptance tests
    to include structured facts, and also bumps the version of the nodes query in
    the facts/find indirector so structured facts are returned when puppet facts
    find is issued to the PDB terminus.

* Split out acceptance and unit test gems in a better way

    We want to avoid installing all of the unit test gems when running acceptance.
    This patch moves rake into its own place so we can use `--without test` with bundler properly.

* Switch confine for basic test during acc dependency installation

    The way we were using confine was wrong, and because this is now more strict
    in beaker it was throwing errors in the master smoke tests. This patch
    just replaces it for a basic include? on the master platform instead.

* Fix old acceptance test refspec issue

    The old refspec for acceptance testing source code only really worked for the
    PR testing workflow. This patch makes it work for the command line or polling
    based workflow as well.

    Without it, it makes it hard to run beaker acceptance tests from the command
    line.

2.1.0
-----

PuppetDB 2.1.0 is a feature release focusing on new query
capabilities, streaming JSON support on all endpoints and a new report
status field for determining if a Puppet run has failed. Note that
this release is backward compatible with 2.0.0, but users must upgrade
PuppetDB terminus to 2.1.0 when upgrading the PuppetDB instance to
2.1.0.

Things to take note of before upgrading:

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 then you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs, and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

* Make sure all your PuppetDB instances are shut down and only upgrade
one at a time.

* As usual, don't forget to upgrade your puppetdb-terminus package
also (on the host where your Puppet master lives), and restart your
master service.

New Features:

* (PDB-660) Switch all query endpoints to stream JSON results

    The following endpoints have been switched over to streaming:

    - event-counts
    - reports
    - nodes
    - environments
    - events

    Using 'event-query-limit' is now deprecated, use the normal
    paging/streaming functionality to achieve the same results.

* (PDB-658, PDB-697) Implement new "query engine" for v4

    This rewrite of the v4 API query infrastructure unifies query
    operators across all endpoints. Each endpoint now supports all
    operators appropriate for the given field of that type. As an
    example, any string field can now be searched by regular expression.
    All dates can be search with inequality operators like < or > for
    searching via date ranges. There are also many new queryable fields.
    Below summarizes the new features of the switch to this query engine

    events endpoint
     - Added configuration-version as a queryable field
     - Added containment-path as a queryable field (queryable in a way similar to tags)

    nodes endpoint
     - Added facts-timestamp, catalog-timestamp, report-timestamp    as a queryable field

    reports endpoint
     - Added puppet-version, report-format, configuration-version, start-time,
         end-time, receive-time, transaction-uuid as queryable fields

    null? operator
     - new operator that checks for the presence or absence of a value

    Some endpoints previously returned NULL values when using a "not"
    query such as ["not", ["=", "line", 10]]. The query engine follows
    SQL semantics, so if you want NULL values, you should explicty ask
    for it like:

    ["or",
        ["not", ["=", "line", 10]]
        ["null?", "line" true]]

* (PDB-162) Add regexp support to resource parameter queries

    The query engine supported this, but the existing "rewrite" rule, to go
    from the shorthand parameter syntax to the nested resource query didn't
    recognize ~. That is fixed with this commit, so regexps will now work on parameters.

* (PDB-601) Do not require query operator on reports endpoint

    With this pull request, hitting the reports endpoint without a query argument
    will return the full reports collection.    This behavior is consistent with
    that of the nodes, facts, and resources endpoints.

* (PDB-651) Allow the web app URL prefix to be configurable

    Previously PuppetDB always used the context root "/", meaning all
    queries etc would be something like
    "http://localhost:8080/v4/version". This change allows users to
    specify a different context root, like
    "http://localhost:8080/my-context-root/v4/version". See the
    url-prefix configuration documentation for more info

* (PDB-16) Add status to stored reports

    Previously there was no way to distinguish between failed puppet runs
    and successful puppet runs as we didn't store report status. This commit
    adds support for report status to the "store report" command, v4 query
    API and model.

* (PDB-700) Allow changes to maxFrameSize in activemq

    maxFrameSize previously defaulted to 100 MB.    Now default is 200 MB with user
    configurability.

Bug fixes and maintenance:

* (PDB-675) Fix Debian/Ubuntu PID missing issue

    In the past in Debian and Ubuntu releases we had issues where the
    PuppetDB system V init scripts were not stopping the PuppetDB
    process whenever a PID file was missing. This patch now introduces a
    fallback that will kill any java process running as the puppetdb
    user, if the PID file is missing.

* (PDB-551) Created a versioning policy document

    This document let's consumers of the PuppetDB API know what to
    expect from an API perspective when new versions of PuppetDB are
    release. This document is a separate page called "Versioning Policy"
    and is included in our API docs

* (PDB-164) Add documentation for select-nodes subquery operator

    This pull request supplies V4 API documentation for the select-nodes subquery
    operator, which was previously supported but undocumented.

* (PDB-720) Fix services test with hard coded Jetty port

    Fixed this issue by moving code that dynamically picks a free port out
    of import-export-roundtrip and into a separate ns. I just switched the
    services test to use that code and there should no longer be conflicts.

* (RE-1497) Remove quantal from build_defaults

    This commit removes quantal from all build defaults because it is end of
    life. It removes the defaults from the build_defaults yaml.

* (PDB-240) Replace anonymize.clj read-string with clojure.edn/read-string

    This patch replaces a call to read-string in anonymize.clj with a call to
    clojure.edn/read-string. Unlike clojure.core/read-string,
    clojure.edn/read-string is safe to use with untrusted data and guaranteed to
    be free of side-effects.

* (PDB-220) Coerce numerical function output in manifests to string

    Previously, when a user defined a numeric-valued function in a puppet manifest
    and submitted it to notify, the resource-title would remain numeric and
    PuppetDB would throw exceptions while storing reports. Per the docs,
    resource-title must be a string. This pull request avoids the problem by
    coercing resource-title to string.

* (PDB-337) Remove extraneous _timestamp fact

    Previously a _timestamp fact was submitted to puppetDB even though _timestamp
    was originally intended for internal use.    This commit strips internal data
    (all preceded by "_") from the factset before submission to PuppetDB.

* (PDB-130) Fixes a nasty traceback exposed when users run import from command line with an invalid filename. A friendly message is now printed instead.

* (PDB-577) Lower KahaDB MessageDatabase logging threshold.

    Previously, premature termination of PuppetDB import under specific ownership
    conditions led to a residual KahaDB lock file that could prevent subsequent imports
    from running with no obvious reason why.    This patch lowers the log threshold
    for KahaDB MessageDataBase so affected users are informed.

* (PDB-686) Add warning about PDB-686 to release notes

    This adds a warning about PDB-686 to the release notes so users know how to
    work-around it.

    This also cleans up the linking in our current release notes, and removes the
    warning about Puppet 3.4.x, because we pin against 3.5.1 and greater anyway.

* Add sbin_dir logic to Rakefile for Arch linux

* (PDB-467) Merge versioning tests for http testing into non-versioned files

    This patch removes all remaining versioned http test files into shared
    unversioned files, so that we may start iterating across versions in the
    same file.

* Fix comparison in dup resources acceptance test

    Due to changes in Puppet 3.6.0, the comparison done in our resource duplication
    tests no longer matches the actual output. This patch ammends the comparison to
    match Puppet 3.6.0 output now.

* (PDB-597) Add trusty build default

    This includes trusty (Ubuntu 14.04) in our builds.

* Unpin the version of beaker

    We had pinned beaker previously because we were waiting for some of our new EC2
    customisations to be merged in and released. This has been done now.

* Fix a race condition in the import/export round-trip clojure tests

    This scenario occurred if command processing for facts is slow. A result
    with the hard coded certname would be returned with no fact values or
    environment. This commit fixes the code to only return results when
    facts are found.

* (PDB-309) Update config conversion code for Schema 0.2.1

    Much of the code for converting user provided config to the internal types (i.e.
    "10" to Joda time 10 Seconds etc) is no longer necessary with the new
    coerce features of Schema. This commit switches to the new version and
    makes the necessary changes to use the coerce feature.

2.0.0
-----

PuppetDB 2.0.0 is a feature release focusing on environments support.
Note that this is a major version bump and there are several breaking
changes, including dropping support for versions of PostgreSQL prior to
version 8.4 and Java 1.6. See the "Deprecations and potentially
breaking changes" section below for more information.

Things to take note of before upgrading:

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 then you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs, and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

* Make sure all your PuppetDB instances are shut down and only upgrade
one at a time.

* As usual, don’t forget to upgrade your puppetdb-terminus package
also (on the host where your Puppet master lives), and restart your
master service.

New features:

* (PDB-452,453,454,456,457,526,557) Adding support for storing, querying and importing/exporting environments

    This change forced a new revision of the `replace facts`,
    `replace catalog` and `store report` commands. The PuppetDB terminus
    also needed to be updated to support new environment information
    being sent to PuppetDB. USERS MUST ALSO UPDATE THE PUPPETDB
    TERMINUS. Previous versions of those commands (and wire formats) are
    now deprecated. See the PuppetDB API docs for more information.
    Environments support has been added to the new v4 (currently
    experimental) query API. The following query endpoints now include
    environment in the response:

    - facts
    - resources
    - nodes
    - catalogs
    - reports
    - events
    - event counts
    - aggregate event counts

    The below query endpoints now allow query/filtering by environment:

    - facts
    - resources
    - nodes
    - catalogs
    - reports
    - events

    This release also includes a new environments query endpoint to list
    all known environments and allow an easy filtering based on
    environment for things like events, facts reports and resources. See
    the query API docs for more information. PuppetDB
    import/export/anonymization and benchmark tool also now have
    environment support. Storeconfigs export does not include
    environments as that information is not being stored in the old
    storeconfigs module. Environments that are no longer associated with
    a fact set, report or catalog will be "garbage collected" by being
    removed from the database.

* (PDB-581) Add subqueries to events query endpoint

    The events endpoint now supports select-resources and select-facts

* (PDB-234) Add v4 query API, deprecate v2 query API

    This patch adds the new code relevant for doing any future v4 work. It has been
    raised as an experimental end-point only so there are no commitments to its
    interface yet. When this is stable, we will need another patch to declare it as so.

    This patch also deprecates the v2 end-point in documentation and by adding the
    same headers we used to use for the v1 end-point.

* (PDB-470) Provide new db setting 'statements-cache-size' with a default of 1000

    This setting adjusts how many SQL prepared statements get cached via BoneCP.

    By using this setting we've seen an almost 40% decrease in wall clock time it
    takes to store a new catalog.

    This patch adds the new configuration item as a user configurable one, with a
    default set to 1000 for now. Documentation has also been added for this
    setting.

* (PDB-221) Add facts to import/export

    This commit imports/exports facts similar to how we currently import/export
    catalogs and reports. Anonymize doesn't currently work for facts, which is
    going to be added separately.

* (PDB-469) - Support Anonymizing Facts

    This commit adds support for the anonymization of facts. The levels of
    anonymization supported are:

    - none - no anonymization
    - low - only values with a fact name of secret, password etc
    - moderate - recognized "safe" facts are untouched, recognized
                                facts with sensitive information (i.e. ipaddress)
                                have their values anonymized
    - full - all fact names and values are anonymized


Deprecations and potentially breaking changes:

* (PDB-88, PDB-271) JDK 1.6 no longer supported

* (PDB-308) Drop 2.7.x support

    This patch removes support for Puppet 2.7.x in several ways:

    * New check for every entry point in terminus will return an error if the
        version of Puppet is not supported. This is done in a 'soft' manner to
        avoid Puppet from not working.
    * Documentation now only references Puppet 3
    * Documentation now states only latest version of Puppet is supported
    * Packaging now has hard dependencies on the latest version of Puppet
    * Contrib gemspec has been updated
    * Gemfile for tests have been updated

* (PDB-552) Pin support for Puppet to 3.5.1 and above

    Starting PuppetDB with a Puppet earlier than 3.5.1 will now fail on startup.

* (PDB-605) Pin facter requirement to 1.7.0

    Using prior versions of Facter will cause PuppetDB to fail on startup

* (PDB-592) Removing support for Ubuntu Raring

    Raring went EOL in Jan 2014, so we are no longer building packages for it.

* (PDB-79) Drop support for Postgres < 8.4

    PuppetDB will now log an error and exit if it connects to an instance of Postgres
    older than 8.4. Users of older versions will need to upgrade (especially EL 5
    users as it defaults to 8.1).    The acceptance tests for EL 5 have been updated
    to be explicit about using Postgres 8.4 packages instead.

* (PDB-204) Ensure all commands no longer need a serialized payload

    Some previous commands required the payload of the command to be
    JSON serialized strings as opposed to the relevant JSON type
    directly in the payload. All commands no longer require the payload
    to be serialized to a string first.

* (PDB-238) - Remove v1 API

    This commit removes the v1 API and builds on the HTTP api refactor.
    This commit contains:

    - Remove all v1 namespaces and the namespaces calling them
    - Remove api tests excercising the v1 routes
    - Remove v1 references in the docs

* (PDB-354) Deprecate old versions of commands

    This patch drops a warning whenever an old version of the commands API is used
    and updates the documentation to warn the user these old commands are
    deprecated.

* (PDB-570) Remove planetarium endpoint

    Old endpoint that had significant overlap with the current catalogs endpoint

* (PDB-113) Remove swank

    As swank is now a deprecated project. This patch removes swank support
    completely from the code base.


Notable improvements and fixes:

* (PDB-473) Support POST using application/json data in the body

    This patch adds to the commands end-point the ability to simply POST using
    application/json with the JSON content in the body.

    It also switches the terminus to use this mechanism.

    We found that the url encode/decode required to support x-www-form-urlencoded
    was actually quite an overhead in a number of ways:

    * The urlencode on the terminus added overhead
    * The urldecode in the server added overhead
    * The interim strings created during this encode/decode process can get quite
        large increasing the amount of garbage collection required

    This feature has been implemented by providing a new middleware that will move
    the body into the parameter :body-string of the request when the content-type
    is not set to application/x-www-form-urlencoded. This provides a convenient
    backwards compatible layer so that the old form url encoding can still be
    supported for older versions of the API.

* (PDB-567,191) Use hash not config_version for report export files

    This fixes a bug related to config_versions containing characts not
    safe to be use in file names (such as '/').

* (PDB-518) Fix bug storeconfig export of arrays

    For exported Resources with parameters which value is a Array the
    storeconfig export fails to collect them. Instead of collecting all
    the parameter values into a array it simply override the value with
    each value in turn.

* (PDB-228) Use JSON in terminus instead of PSON

    The PuppetDB API specifies that it is JSON, so we should parse it as
    that and not as PSON.

    Some Puppet classes (Puppet::Node and Puppet::Node::Facts) don't
    support JSON serialization, so continue to use PSON serialization
    for them. In Puppet 3.4.0+ they have methods to do seralization in
    other formats than PSON though, so when support for older versions
    of Puppet is dropped they can be seralized in JSON as well.

* (PDB-476) Decorate the terminus code with Puppet profiling blocks

    This patch adds some select profiling blocks to the PuppetDB terminus code.

    The profiler is provided by puppet core from Puppet::Util::Puppetdb#profile,
    which has recently become public for our use. We provide here in our own utils
    library our own wrapper implementation that can be mixed in.

    Key areas of our terminus functionality have now been profiled with this
    patch:

    * Entry points are profiled and identified by their entry methods (save, find,
        search etc.)
    * Remote calls, HTTP gets/posts
    * Code that does any form of encoding/decoding that might be potentially slow
        at capacity.

    The style of messages I've used follow along with the existing Puppet profiling
    examples already in place so as to be readable together. We have prefixed our
    profile message with "PuppetDB" for easy searchability also.

    I have provided a small FAQ entry that explains in brief the process of
    debugging, although we lack something to link to in Puppet for a more detailed
    explanation. This will probably need to be fixed if better documentation comes
    available.

* (PDB-472) - Annotate MQ messages without parsing payload

    Received time and a UUID are currently added to incoming (via HTTP) messages
    before placing them on the queue. This commit adds those annotations to the
    MQ message header no longer requires parsing the incoming message payload
    before placing it on the queue.

* (PDB-87) Port PuppetDB to TrapperKeeper

    TrapperKeeper is a new container that PuppetDB will be deployed in.
    This is mainly a refactoring of existing code and error handling to
    use the centralized TrapperKeeper service. More information on
    TrapperKeeper can be found here:
    http://puppetlabs.com/blog/new-era-application-services-puppet-labs.

* (PDB-401) Upgrade to TrapperKeeper 0.3.4

    This commit updates PuppetDB to use the new trapperkeeper 0.3.4
    API.    This includes:

    * Slightly modified syntax for defining services and service
        lifecycle behavior
    * Switch from log4j to logback, update documentation and packaging
        accordingly
    * Switch from jetty7 to jetty9
    * Add example of how to use "reloaded" interactive development pattern
        in REPL
    * Upgrade to kitchensink 0.5.3, with bouncycastle fix for improved
        HTTPS performance

* (PDB-529) Added latest-report? example to the events docs

* (PDB-512) Upgrade to Clojure 1.6.0

* (PDB-521) Switch to using /dev/urandom (using java.security.egd)

* (PDB-564) Added OpenBSD-specific variables to puppetdb.env

    Adding OpenBSD specific variables allows the OpenBSD package
    maintained downstream in the OpenBSD ports tree to be greatly
    simplified.

* (PDB-177) Replace ssl-host default with 0.0.0.0

    By trying to use a hostname, the amount of issues people suffer with during
    setup times related to hostname resolution is quite high. This patch
    replaces the hostname with 0.0.0.0 which by default listens on all
    interfaces.

* (PDB-402) Remove ahead-of-time compilation

    This patch removes AOT compilation from our leiningen project and updates
    all relevant shell scripts to use the non-AOT methodology for invoking
    clojure projects.

* (PDB-576) Update beaker tests to use host.hostname instead of host.name

* (PDB-481) Added Arch Linux build/install support

* (PDB-572) New community packages of puppet install in vendorlibdir

* (PDB-575) Updated install from source docs

* (PDB-254)  Change benchmark to mutate catalog resources and edges send a defined number of messages

    Previously benchmark.clj when it would mutate a catalog would only
    add a single resource.  This change will add a new resource or
    mutate a random existing resource.  It will also add a new or
    change an existing edge.    One of these four mutations is picked
    at random.  This commit also adds a new parameter to benchmark.clj
    to allow the syncronous sending of a specified number of commands
    (per host) via the -N argument

* (PDB-591) Allow gem source to come from env vars

* (PDB-595) Added docs for the load testing and benchmarking tool

    Mostly a developer tool, but documented how to use it in case it's
    useful to others. See "Load Testing" in the Usage / Admin section of
    the docs site

* (PDB-602) Updated acceptance tests to use a proper release of leiningen

* (DOCUMENT-6) Update config page for PuppetDB module's improved settings behavior


1.6.3
-----

PuppetDB 1.6.3 is a bugfix release.

Notable improvements and fixes:

* (PDB-510) Add migration to fix sequence for catalog.id

    The sequence for catalog.id had not been incremented during insert for migration
    20 'differential-catalog-resources'.

    This meant that the catalog.id column would throw a constraint exception during
    insertion until the sequence had increased enough to be greater then the max id
    already used in the column.

    While this was only a temporary error, it does cause puppetdb to start to throw
    errors and potentially dropping some catalog updates until a certain number of
    catalogs had been attempted. In some cases catalogs had been queued up and
    retried successfully, other cases meant they were simply dropped into the DLQ.

    The fix is to reset the sequence to match the max id on the catalogs.id column.

* RHEL 7 beta packages are now included

* (PDB-343) Fedora 18 packages are no longer to be built

* (PDB-468) Update CLI tools to use correct JAVA_BIN and JAVA_ARGS

    Some tools such as `puppetdb import|export` and `puppetdb foreground` where not
    honouring the JAVA_BIN defined in /etc/default|sysconfig/puppetdb.

* (PDB-437) Reduce API source code and version inconsistencies

    The API code when split between different versions created an opportunity for
    inconsistencies to grow. For example, some versioning inside the code supported
    this file based way of abstracting versions, but other functions required
    version specific handling.

    This patch solidifies the version handling to ensure we reduce in regressions
    relating to different versions of the query API and different operator handling.

* Use v3 end-point for import and benchmark tools

* (PDB-80)(packaging) Fixup logic in defaults file for java on EL

* (PDB-463) Fix assertion error in /v1/resources

* (PDB-238) Some code reduction work related to simplifying future query API version removal

* (PDB-446) Start in on the merge of v2 and v3 test namespaces for testing

* (PDB-435) Travis no longer has bundler installed by default, now installing it explicitly

* (PDB-437) Clojure lint cleanups

* Change tutorial and curl documentation examples to use v3 API

* Added examples to documentation for latest-report? and file

1.6.2
-----

PuppetDB 1.6.2 is a bugfix release.

Notable improvements and fixes:


* Provided an early release RPM SPEC for RHEL 7, no automated builds yet.

* (PDB-377) - Fixed a Fedora RPM packaging issue preventing PuppetDB
    from starting by disabling JAR repacking

* (PDB-341) - Fixed a naming issue when using subqueries for resource
    metadata like file and line with v3 of the API

* (PDB-128) - Oracle Java 7 package support

    Add support for Oracle Java 7 packages on Debian. This
    means that users who have older Debian based distros but do not have
    native JDK 7 packages can build their own Oracle Java 7 package (see
    https://wiki.debian.org/JavaPackage) and we will pull it in as a
    dependency.

* (PDB-106) - Added an explicit log message upon a failed agent run
    (previously would fail with “undefined method `[]' for nil:NilClass”)

* Change the order that filters are applied for events

    When using the `distinct-resources` flag of an event query, the
    previous behavior was that we would do the filtering of the events
    *before* we would eliminate duplicate resources. This was not the
    expected behavior in many cases in the UI; for example, when filtering
    events based on event status, the desired behavior was to find all of
    the most recent events for each resource *first*, and then apply the
    filter to that set of resources. If we did the status filtering first,
    then we might end up in a state where we found the most recent
    'failed' event and showed it in the UI even if there were 'success'
    events on that resource afterwards.

    This commit changes the order that the filtering happens in.    We
    now do the `distinct` portion of the query before we do the filtering.

    However, in order to achieve reasonable performance, we need to
    at least include timestamp filtering in the `distinct` query; otherwise
    that portion of the query has to work against the entire table,
    and becomes prohibitively expensive.

    Because the existing timestamp filtering can be nested arbitrarily
    inside of the query (inside of boolean logic, etc.), it was not
    going to be possible to re-use that to handle the timestamp filtering
    for the `distinct` part of the query; thus, we had to introduce
    two new query parameters to go along with `distinct-resources`:
    `distinct-start-time` and `distinct-end-time`.  These are now
    required when using `distinct-resources`.

* (PDB-407) - Add Fedora 20 acceptance tests

* (PDB-425) - System V to SystemD upgrade

    Fixed issues upgrading from 1.5.2 to 1.6.2 on Fedora.
    1.5.2 used System V scripts while 1.6.x used SystemD which caused
    failures.

1.6.1
-----

Not released due to SystemD-related packaging issues on Fedora


1.6.0
-----

PuppetDB 1.6.0 is a performance and bugfix release.

Notable improvements and fixes:

* (PDB-81) Deprecate JDK6. It's been EOL for quite some time.

* (#21083) Differential fact storage

    Previously when facts for a node were replaced, all previous facts
    for that node were deleted and all new facts were inserted. Now
    existing facts are updated, old facts (no longer present) are
    deleted and new facts are inserted. This results in much less I/O,
    both because we have to write much less data and also because we
    reduce churn in the database tables, allowing them to stay compact
    and fast.

* (PDB-68) Differential edge storage

    Previously when a catalog wasn't detected as a duplicate, we'd have
    to reinsert all edges into the database using the new catalog's
    hash. This meant that even if 99% of the edges were the same, we'd
    still insert 100% of them anew and wait for our periodic GC to clean
    up the old rows. We now only modify the edges that have actually
    changed, and leave unchanged edges alone. This results in much less
    I/O as we touch substantially fewer rows.

* (PDB-69) Differential resource storage

    Previously when a catalog wasn't detected as a duplicate, we'd have
    to reinsert all resource metadata into the catalog_resources table
    using the catalog's new hash. Even if only 1 resource changed out of
    a possible 1000, we'd still insert 1000 new rows. We now only modify
    resources that have actually changed. This results in much less I/O
    in the common case.

* Streaming resource and fact queries. Previously, we'd load all rows
    from a resource or fact query into RAM, then do a bunch of sorting
    and aggregation to transform them into a format that clients
    expect. That has obvious problems involving RAM usage for large
    result sets. Furthermore, this does all the work for querying
    up-front...if a client disconnects, the query continues to tax the
    database until it completes. And lastly, we'd have to wait until all
    the query results have been paged into RAM before we could send
    anything to the client. New streaming support massively reduces RAM
    usage and time-to-first-result. Note that currently only resource
    and fact queries employ streaming, as they're the most
    frequently-used endpoints.

* Improvements to our deduplication algorithm. We've improved our
    deduplication algorithms to better detect when we've already stored
    the necessary data. Early reports from the field has show users who
    previously had deduplication rates in the 0-10% range jumping up to
    the 60-70% range. This has a massive impact on performance, as the
    fastest way to persist data is to already have it persisted!

* Eliminate joins for parameter retrieval. Much of the slowness of
    resource queries comes from the format of the resultset. We get back
    one row per parameter, and we need to collapse multiple rows into
    single resource objects that we can emit to the client. It's much
    faster and tidier to just have a separate table that contains a
    json-ified version of all a resource's parameters. That way, there's
    no need to collapse multiple rows at all, or do any kind of ORDER BY
    to ensure that the rows are properly sequenced.  In testing, this
    appears to speed up queries between 2x-3x...the improvement is much
    better the larger the resultset.

* (#22350) Support for dedicated, read-only databases. Postgres
    supports Hot Standby (http://wiki.postgresql.org/wiki/Hot_Standby)
    which uses one database for writes and another database for
    reads. PuppetDB can now point read-only queries at the hot standby,
    resulting in improved IO throughput for writes.

* (#22960) Don't automatically sort fact query results

    Previously, we'd sort fact query results by default, regardless of
    whether or not the user has requested sorting. That incurs a
    performance penalty, as the DB has to now to a costly sort
    operation. This patch removes the sort, and if users want sorted
    results they can use the new sorting parameters to ask for that
    explicitly.

* (#22947) Remove resource tags GIN index on Postgres. These indexes
    can get large and they aren't used. This should free up some
    precious disk space.

* (22977) Add a debugging option to help diagnose catalogs for a host
    that hash to different values

    Added a new global config parameter to allow debugging of catalogs
    that hash to a different value. This makes it easier for users to
    determine why their catalog duplication rates are low. More details
    are in the included "Troubleshooting Low Catalog Duplication" guide.

* (PDB-56) Gzip HTTP responses

    This patchset enables Jetty's gzip filter, which will automatically
    compress output with compressible mime-types (text, JSON, etc). This
    should reduce bandwidth requirements for clients who can handle
    compressed responses.

* (PDB-70) Add index on catalog_resources.exported

    This increases performance for exported resource collection
    queries. For postgresql the index is only a partial on exported =
    true, because indexing on the very common 'false' case is not that
    effective. This gives us a big perf boost with minimal disk usage.

* (PDB-85) Various fixes for report export and anonymization

* (PDB-119) Pin to RSA ciphers for all jdk's to work-around Centos 6
    EC failures

    We were seeing EC cipher failures for Centos 6 boxes, so we now pin
    the ciphers to RSA only for all JDK's to work-around the
    problem. The cipher suite is still customizable, so users can
    override this is they wish.

* Fixes to allow use of public/private key files generated by a wider
    variety of tools, such as FreeIPA.

* (#17555) Use systemd on recent Fedora and RHEL systems.

* Documentation for `store-usage` and `temp-usage` MQ configuration
    options.

* Travis-CI now automatically tests all pull requests against both
    PostgreSQL and HSQLDB. We also run our full acceptance test suite on
    incoming pull requests.

* (PDB-102) Implement Prismatic Schema for configuration validation

    In the past our configuration validation was fairly ad-hoc and imperative. By
    implementing an internal schema mechanism (using Prismatic Schema) this should
    provice a better and more declarative mechanism to validate users
    configuration rather then letting mis-configurations "fall through" to
    internal code throwing undecipherable Java exceptions.

    This implementation also handles configuration variable coercion and
    defaulting also, thus allowing us to remove a lot of the bespoke code we had
    before that performed this activity.

* (PDB-279) Sanitize report imports

    Previously we had a bug PDB-85 that caused our exports on 1.5.x to fail. This
    has been fixed, but alas people are trying to import those broken dumps into
    1.6.x and finding it doesn't work.

    This patch sanitizes our imports by only using select keys from the reports
    model and dropping everything else.

* (PDB-107) Support chained CA certificates

    This patch makes puppetdb load all of the certificates in the CA .pem
    file into the in-memory truststore. This allows users to use a
    certificate chain (typically represented as a sequence of certs in a
    single file) for trust.

1.5.2
-----

PuppetDB 1.5.2 is a maintenance and bugfix release.

Notable changes and fixes:

* Improve handling of logfile names in our packaging, so that it's easier to
    integrate with tools like logrotate.

* Better error logging when invoking subcommands.

* Fix bugs in `order-by` support for `facts`, `fact-names`, and `resources` query endpoints.

* Documentation improvements.

* Add packaging support for Ubuntu `saucy`.

* Add support for PEM private keys that are not generated by Puppet, and are not
    represented as key pairs.

* Fix inconsistencies in names of `:sourcefile` / `:sourceline` parameters when
    using `nodes/<node>/resources` version of `nodes` endpoint; these were always
    being returned in the `v2` response format, even when using `/v3/nodes`.

1.5.1
-----

NOTE: This version was not officially released, as additional fixes
came in between the time we tagged this and the time we were going to
publish release artifacts.

1.5.0
-----

PuppetDB 1.5.0 is a new feature release.    (For detailed information about
any of the API changes, please see the official documentation for PuppetDB 1.5.)

Notable features and improvements:

* (#21520) Configuration for soft failure when PuppetDB is unavailable

    This feature adds a new option 'soft_write_failure' to the puppetdb
    configuration.  If enabled the terminus behavior is changed so that if a
    command or write fails, instead of throwing an exception and causing the agent
    to stop it will simply log an error to the puppet master log.

* New v3 query API

    New `/v3` URLs are available for all query endpoints.    The `reports` and
    `events` endpoints, which were previously considered `experimental`, have
    been moved into `/v3`.  Most of the other endpoints are 100% backwards-compatible
    with `/v2`, but now offer additional functionality.  There are few minor
    backwards-incompatible changes, detailed in the comments about individual
    endpoints below.

* Query paging

    This feature adds a set of new HTTP query parameters that can be used with most
    of the query endpoints (`fact_names`, `facts`, `resources`, `nodes`, `events`,
    `reports`, `event-counts`) to allow paging through large result sets over
    multiple queries.    The available HTTP query parameters are:

    * `limit`: an integer specifying the maximum number of results to
           return.
    * `order-by`: a list of fields to sort by, in ascending or descending order.
           The legal set of fields varies by endpoint; see the documentation for
           individual endpoints for more info.
    * `offset`: an integer specifying the first result in the result set that
           should be returned.  This can be used in combination with `limit`
           and `order-by` to page through a result set over multiple queries.
    * `include-total`: a boolean flag which, if set, will cause the HTTP response
        to contain an `X-Records` header indicating the total number of results that are
        available that match the query.    (Mainly useful in combination with `limit`.)

* New features available on `events` endpoint

    * The `events` data now contains `file` and `line` fields.  These indicate
        the location in the manifests where the resource was declared.  They can
        be used as input to an `events` query.
    * Add new `configuration-version` field, which contains the value that Puppet
        supplied during the agent run.
    * New `containing-class` field: if the resource is declared inside of a
        Puppet class, this field will contain the name of that class.
    * New `containment-path` field: this field is an array showing the full
        path to the resource from the root of the catalog (contains an ordered
        list of names of the classes/types that the resource is contained within).
    * New queryable timestamp fields:
            * `run-start-time`: the time (on the agent node) that the run began
            * `run-end-time`: the time (on the agent node) that the run completed
            * `report-receive-time`: the time (on the puppetdb node) that the report was received by PuppetDB
    * Restrict results to only include events that occurred in the latest report
        for a given node: `["=", "latest-report?", true]`

* New `event-counts` endpoint

    `v3` of the query API contains a new `event-counts` endpoint, which can be
    used to retrieve count data for an event query.  The basic input to the
    endpoint is an event query, just as you'd provide to the `events` endpoint,
    but rather than returning the actual events, this endpoint returns counts
    of `successes`, `failures`, `skips`, and `noops` for the events that match
    the query.  The counts may be aggregated on a per-resource, per-class,
    or per-node basis.

* New `aggregate-event-counts` endpoint

    This endpoint is similar to the `event-counts` endpoint, but rather than
    aggregating the counts on a per-node, per-resource, or per-class basis,
    it returns aggregate counts across your entire population.

* New `server-time` endpoint

    This endpoint simply returns a timestamp indicating the current time on
    the PuppetDB server.    This can be used as input to time-based queries
    against timestamp fields that are populated by PuppetDB.

* Minor changes to `resources` endpoint for `v3`

    The `sourcefile` and `sourceline` fields have been renamed to `file` and `line`,
    for consistency with other parts of the API.

* Minor changes relating to reports storage and query

    * `store report` command has been bumped up to version `2`.
    * Report data now includes a new `transaction-uuid` field; this is generated
        by Puppet (as of Puppet 3.3) and can be used to definitively correlate a report
        with the catalog that was used for the run.  This field is queryable on the
        `reports` endpoint.
    * Reports now support querying by the field `hash`; this allows you to retrieve
        data about a given report based on the report hash for an event returned
        by the `events` endpoint.

* Minor changes relating to catalog storage

    * `store catalog` command has been bumped to version `3`.
    * Catalog data now includes the new `transaction-uuid` field; see notes above.

Bug fixes:

* PuppetDB report processor was truncating microseconds from report timestamps;
    all timestamp fields should now retain full precision.

* Record resource failures even if Puppet doesn't generate an event for them in the
    report: in rare cases, Puppet will generate a report that indicates a failure
    on a resource but doesn't actually provide a failure event.  Prior to PuppetDB
    1.5, the PuppetDB report processor was only checking for the existence of
    events, so these resources would not show up in the PuppetDB report.    This is
    really a bug in Puppet (which should be fixed as of Puppet 3.3), but the PuppetDB
    report processor is now smart enough to detect this case and synthesize a failure
    event for the resource, so that the failure is at least visible in the PuppetDB
    report data.

* Filter out the well-known "Skipped Schedule" events: in versions of Puppet prior
    to 3.3, every single agent report would include six events whose status was
    `skipped` and whose resource type was `Schedule`.    (The titles were `never`,
    `puppet`, `hourly`, `daily`, `weekly`, and `monthly`.)  These events were not
    generally useful and caused a great deal of pollution in the PuppetDB database.
    They are no longer generated as of Puppet 3.3, but for compatibility with
    older versions of Puppet, the report terminus in PuppetDB 1.5 will filter
    these events out before storing the report in PuppetDB.

* Log a message when a request is blocked due to the certificate whitelist:
    prior to 1.5, when a query or command was rejected due to PuppetDB's certificate
    whitelist configuration, there was no logging on the server that could be used
    to troubleshoot the cause of the rejection.  We now log a message, in hopes of
    making it easier for administrators to track down the cause of connectivity
    issues in this scenario.

* (#22122) Better log messages when puppetdb-ssl-setup is run before Puppet
    certificates are available.

* (#22159) Fix a bug relating to anonymizing catalog edges in exported PuppetDB
    data.

* (#22168) Add ability to configure maximum number of threads for Jetty (having too
    low of a value for this setting on systems with large numbers of cores could
    prevent Jetty from handling requests).

1.4.0
-----

PuppetDB 1.4.0 is a new feature release.

Notable features and improvements:

* (#21732) Allow SSL configuration based on Puppet PEM files (Chris Price & Ken Barber)

    This feature introduces some functions for reading keys and
    certificates from PEM files, and dynamically constructing java
    KeyStore instances in memory without requiring a .jks file on
    disk.

    It also introduces some new configuration options that may
    be specified in the `jetty` section of the PuppetDB config
    to initialize the web server SSL settings based on your
    Puppet PEM files.

    The tool `puppetdb-ssl-setup` has been modified now to handle these new
    parameters, but leave legacy configuration alone by default.

* (#20801) allow */* wildcard (Marc Fournier)

    This allows you to use the default "Accept: */*" header to retrieve JSON
    documents from PuppetDB without needed the extra "Accept: applicaiton/json"
    header when using tools such as curl.

* (#15369) Terminus for use with puppet apply (Ken Barber)

    This patch provides a new terminus that is suitable for facts storage usage
    with masterless or `puppet apply` usage. The idea is that it acts as a fact
    cache terminus, intercepting the first save request and storing the values
    in PuppetDB.

* Avoid Array#find in Puppet::Resource::Catalog::Puppetdb#find_resource (Aman Gupta)

    This patch provides performance improvements in the terminus, during the
    synthesize_edges stage. For example, in cases with 10,000 resource (with
    single relationships) we saw a reduction from 83 seconds to 6 seconds for a
    full Puppet run after this patch was applied.

* Portability fixes for OpenBSD (Jasper Lievisse Adriaanse)

    This series of patches from Jasper improved the scripts in PuppetDB so they
    are more portable to BSD based platforms like OpenBSD.

* Initial systemd service files (Niels Abspoel)

* Updated spec file for suse support (Niels Abspoel)

    This change wil make puppetdb rpm building possible on opensuse
    with the same spec file as redhat.

* (#21611) Allow rake commands to be ran on Ruby 2.0 (Ken Barber)

    This allows rake commands to be ran on Ruby 2.0, for building on Fedora 19
    to be made possible.

* Add puppetdb-anonymize tool (Ken Barber)

    This patch adds a new tool 'puppetdb-anonymize' which provides users with a
    way to anonymize/scrub their puppetdb export files so they can be shared
    with third parties.

* (#21321) Configurable SSL protocols (Deepak Giridharagopal)

    This patch adds an additional configuration option, `ssl-protocols`, to
    the `[jetty]` section of the configuration file. This lets users specify
    the exact list of SSL protocols they wish to support, such as in cases
    where they're running PuppetDB in an environment with strict standards
    for SSL usage.

    If the option is not supplied, we use the default set of protocols
    enabled by the local JVM.

* Create new conn-lifetime setting (Chuck Schweizer & Deepak Giridharagopal)

    This creates a new option called `conn-lifetime` that governs how long
    idle/active connections stick around.

* (#19174) Change query parameter to optional for facts & resources (Ken Barber)

    Previously for the /v2/facts and /v2/resources end-point we had documented that
    the query parameter was required, however a blank query parameter could be used
    to return _all_ data, so this assertion wasn't quite accurate. However one
    could never really drop the query parameter as it was considered mandatory and
    without it you would get an error.

    To align with the need to return all results at times, and the fact that
    making a query like '/v2/facts?query=' to do such a thing is wasteful, we have
    decided to drop the mandatory need for the 'query' parameter.

    This patch allows 'query' to be an optional parameter for /v2/facts & resources
    by removing the validation check and updating the documentation to reflect this
    this new behaviour.

    To reduce the risk of memory bloat, the settings `resource-query-limit` still
    apply, you should use this to set the maximum amount of resources in a single
    query to provide safety from such out of memory problems.

Bug fixes:

* Fix the -p option for puppetdb-export/import (Ken Barber)

* Capture request metrics on per-url/per-path basis (Deepak Giridharagopal)

    When we migrated to versioned urls, we didn't update our metrics
    middleware. Originally, we had urls like /resources, /commands, etc.
    We configured the metrics middlware to only take the first component of
    the path and create a metric for that, so we had metrics that tracked
    all requests to /resources, /commands, etc. and all was right with the
    world.

    When we moved to versioned urls, though, the first path component became
    /v1, /v2, etc. This fix now allows the user to provide full URL paths to
    query specific end-points, while still supporting the older mechanism of
    passing 'commands', 'resources',    and 'metrics'.

* (21450) JSON responses should be UTF-8 (Deepak Giridharagopal)

    JSON is UTF-8, therefore our responses should also be UTF-8.

Other important changes & refactors:

* Upgrade internal components, including clojure (Deepak Giridharagopal)

    - upgrade clojure to 1.5.1
    - upgrade to latest cheshire, nrepl, libs, tools.namespace, clj-time, jmx,
        ring, at-at, ring-mock, postgresql, log4j

* Change default db conn keepalive to 45m (Deepak Giridharagopal)

    This better matches up with the standard firewall or load balancer
    idle connection timeouts in the wild.

1.3.2
-----

PuppetDB 1.3.2 is a bugfix release.  Many thanks to the following
people who contributed patches to this release:

* Chris Price

Bug fixes:

* Size of column `puppet_version` in the database schema is insufficient

    There is a field in the database that is used to store a string
    representation of the puppet version along with each report.    Previously,
    this column could contain a maximum of 40 characters, but for
    certain builds of Puppet Enterprise, the version string could be
    longer than that.    This change simply increases the maximum length of
    the column.

1.3.1
-----

PuppetDB 1.3.1 is a bugfix release.  Many thanks to the following
people who contributed patches to this release:

* Chris Price
* Deepak Giridharagopal
* Ken Barber
* Matthaus Owens
* Nick Fagerlund

Bug fixes:

* (#19884) Intermittent SSL errors in Puppet master / PuppetDB communication

    There is a bug in OpenJDK 7 (starting in 1.7 update 6) whereby SSL
    communication using Diffie-Hellman ciphers will error out a small
    percentage of the time.  In 1.3.1, we've made the list of SSL ciphers
    that will be considered during SSL handshake configurable.  In addition,
    if you're using an affected version of the JDK and you don't specify
    a legal list of ciphers, we'll automatically default to a list that
    does not include the Diffie-Hellman variants.    When this issue is
    fixed in the JDK, we'll update the code to re-enable them on known
    good versions.

* (#20563) Out of Memory error on PuppetDB export

    Because the `puppetdb-export` tool used multiple threads to retrieve
    data from PuppetDB and a single thread to write the data to the
    export file, it was possible in certain hardware configurations to
    exhaust all of the memory available to the JVM.  We've moved this
    back to a single-threaded implementation for now, which may result
    in a minor performance decrease for exports, but will prevent
    the possibility of hitting an OOM error.

* Don't check for newer versions in the PE-PuppetDB dashboard

    When running PuppetDB as part of a Puppet Enterprise installation, the
    PuppetDB package should not be upgraded independently of Puppet Enterprise.
    Therefore, the notification message that would appear in the PuppetDB
    dashboard indicating that a newer version is available has been removed
    for PE environments.


1.3.0
-----

Many thanks to the following people who contributed patches to this
release:

* Branan Purvine-Riley
* Chris Price
* Deepak Giridharagopal
* Ken Barber
* Matthaus Owens
* Moses Mendoza
* Nick Fagerlund
* Nick Lewis

Notable features:

* Report queries

    The query endpoint `experimental/event` has been augmented to support a
    much more interesting set of queries against report data.    You can now query
    for events by status (e.g. `success`, `failure`, `noop`), timestamp ranges,
    resource types/titles/property name, etc.    This should make the report
    storage feature of PuppetDB *much* more valuable!

* Import/export of PuppetDB reports

    PuppetDB 1.2 added the command-line tools `puppetdb-export` and `puppetdb-import`,
    which are useful for migrating catalog data between PuppetDB databases or
    instances.  In PuppetDB 1.3, these tools now support importing
    and exporting report data in addition to catalog data.

Bug fixes:

* `puppetdb-ssl-setup` is now smarter about not overwriting keystore
    settings in `jetty.ini` during upgrades

* Add database index to `status` field for events to improve query performance

* Fix `telnet` protocol support for embedded nrepl

* Upgrade to newer version of nrepl

* Improvements to developer experience (remove dependency on `rake` for
    building/running clojure code)

1.2.0
-----

Many thanks to following people who contributed patches to this
release:

* Chris Price
* Deepak Giridharagopal
* Erik Dalén
* Jordi Boggiano
* Ken Barber
* Matthaus Owens
* Michael Hall
* Moses Mendoza
* Nick Fagerlund
* Nick Lewis

Notable features:

* Automatic node purging

    This is the first feature which allows data in PuppetDB to be deleted. The
    new `node-purge-ttl` setting specifies a period of time to keep deactivated
    nodes before deleting them. This can be used with the `puppet node
    deactivate` command or the automatic node deactivation `node-ttl` setting.
    This will also delete all facts, catalogs and reports for the purged nodes.
    As always, if new data is received for a deactivated node, the node will be
    reactivated, and thus exempt from purging until it is deactivated again. The
    `node-purge-ttl` setting defaults to 0, which disables purging.

* Import/export of PuppetDB data

    Two new commands have been added, `puppetdb-export` and `puppetdb-import`.
    These will respectively export and import the entire collection of catalogs
    in your PuppetDB database. This can be useful for migrating from HSQL to
    PostgreSQL, for instance.

    There is also a new Puppet subcommand, `puppet storeconfigs export`. This
    command will generate a similar export data from the ActiveRecord
    storeconfigs database. Specifically, this includes only exported resources,
    and is useful when first migrating to PuppetDB, in order to prevent failures
    due to temporarily missing exported resources.

* Automatic dead-letter office compression

    When commands fail irrecoverably or over a long period of time, they are
    written to disk in what is called the dead-letter office (or DLO). Until now,
    this directory had no automatic maintenance, and could rapidly grow in size.
    Now there is a `dlo-compression-threshold` setting, which defaults to 1 day,
    after which commands in the DLO will be compressed. There are also now
    metrics collected about DLO usage, several of which (size, number of
    messages, compression time) are visible from the PuppetDB dashboard.

* Package availability changes

    Packages are now provided for Fedora 18, but are no longer provided for
    Ubuntu 11.04 Natty Narwhal, which is end-of-life. Due to work being done to
    integrate PuppetDB with Puppet Enterprise, new pe-puppetdb packages are not
    available.

Bug fixes:

* KahaDB journal corruption workaround

    If the KahaDB journal, used by ActiveMQ (in turn used for asynchronous
    message processing), becomes corrupted, PuppetDB would fail to start.
    However, if the embedded ActiveMQ broker is restarted, it will cleanup the
    corruption itself. Now, PuppetDB will recover from such a failure and restart
    the broker automatically.

* Terminus files conflict between puppetdb-terminus and puppet

    There was a conflict between these two packages over ownership of certain
    directories which could cause the puppetdb-terminus package to fail to
    install in some cases. This has been resolved.

1.1.1
-----

PuppetDB 1.1.1 is a bugfix release.  It contains the following fixes:

* (#18934) Dashboard Inventory Service returns 404

    Version 1.1.0 of the PuppetDB terminus package contained a faulty URL for
    retrieving fact data for the inventory service.  This issue is fixed and
    we've added better testing to ensure that this doesn't break again in the
    future.

* (#18879) PuppetDB terminus 1.0.5 is incompatible with PuppetDB 1.1.0

    Version 1.1.0 of the PuppetDB server package contained some API changes that
    were not entirely backward-compatible with version 1.0.5 of the PuppetDB
    terminus; this caused failures for some users if they upgraded the server
    to 1.1.0 without simultaneously upgrading the terminus package.  Version 1.1.1
    of the server is backward-compatible with terminus 1.0.5, allowing an easier
    upgrade path for 1.0.x users.


1.1.0
-----

Many thanks to the following people who contributed patches to this
release:

* Chris Price
* Deepak Giridharagopal
* Jeff Blaine
* Ken Barber
* Kushal Pisavadia
* Matthaus Litteken
* Michael Stahnke
* Moses Mendoza
* Nick Lewis
* Pierre-Yves Ritschard

Notable features:

* Enhanced query API

    A substantially improved version 2 of the HTTP query API has been added. This
    is located under the /v2 route. Detailed documentation on all the available
    routes and query language can be found in the API documentation, but here are
    a few of the noteworthy improvements:

    * Query based on regular expressions

        Regular expressions are now supported against most fields when querying
        against resources, facts, and nodes, using the ~ operator. This makes it
        easy to, for instance, find *all* IP addresses for a node, or apply a query
        to some set of nodes.

    * More node information

        Queries against the /v2/nodes endpoint now return objects, rather than
        simply a list of node names. These are effectively the same as what was
        previously returned by the /status endpoint, containing the node name, its
        deactivation time, as well as the timestamps of its latest catalog, facts,
        and report.

    * Full fact query

        The /v2/facts endpoint supports the same type of query language available
        when querying resources, where previously it could only be used to retrieve
        the set of facts for a given node. This makes it easy to find the value of
        some fact for all nodes, or to do more complex queries.

    * Subqueries

        Queries can now contain subqueries through the `select-resources` and
        `select-facts` operators. These operators perform queries equivalent to
        using the /v2/resources and /v2/facts routes, respectively. The information
        returned from them can then be correlated, to perform complex queries such
        as "fetch the IP address of all nodes with `Class[apache]`", or "fetch the
        `operatingsystemrelease` of all Debian nodes". These operators can also be
        nested and correlated on any field, to answer virtually any question in a
        single query.

    * Friendlier, RESTful query routes

        In addition to the standard query language, there are also now more
        friendly, "RESTful" query routes. For instance, `/v2/nodes/foo.example.com`
        will return information about the node foo.example.com. Similarly,
        `/v2/facts/operatingsystem` will return the `operatingsystem` of every node, or
        `/v2/nodes/foo.example.com/operatingsystem` can be used to just find the
        `operatingsystem` of foo.example.com.

        The same sort of routes are available for resources as well.
        `/v2/resources/User` will return every User resource, `/v2/resources/User/joe`
        will return every instance of the `User[joe]` resource, and
        `/v2/nodes/foo.example.com/Package` will return every Package resource on
        foo.example.com. These routes can also have a query parameter supplied, to
        further query against their results, as with the standard query API.

* Improved catalog storage performance

     Some improvements have been made to the way catalog hashes are computed for
     deduplication, resulting in somewhat faster catalog storage, and a
     significant decrease in the amount of time taken to store the first catalog
     received after startup.

* Experimental report submission and storage

    The 'puppetdb' report processor is now available, which can be used
    (alongside any other reports) to submit reports to PuppetDB for storage. This
    feature is considered experimental, which means the query API may change
    significantly in the future. The ability to query reports is currently
    limited and experimental, meaning it is accessed via /experimental/reports
    rather than /v2/reports. Currently it is possible to get a list of reports
    for a node, and to retrieve the contents of a single report. More advanced
    querying (and integration with other query endpoints) will come in a future
    release.

    Unlike catalogs, reports are retained for a fixed time period (defaulting to
    7 days), rather than only the most recent report being stored. This means
    more data is available than just the latest, but also prevents the database
    from growing unbounded. See the documentation for information on how to
    configure the storage duration.

* Tweakable settings for database connection and ActiveMQ storage

    It is now possible to set the timeout for an idle database connection to be
    terminated, as well as the keep alive interval for the connection, through
    the `conn-max-age` and `conn-keep-alive` settings.

    The settings `store-usage` and `temp-usage` can be used to set the amount of
    disk space (in MB) for ActiveMQ to use for permanent and temporary message
    storage. The main use for these settings is to lower the usage from the
    default of 100GB and 50GB respectively, as ActiveMQ will issue a warning if
    that amount of space is not available.

Behavior changes:

* Messages received after a node is deactivated will be processed

    Previously, commands which were initially received before a node was
    deactivated, but not processed until after (for instance, because the first
    attempt to process the command failed, and the node was deactivated before
    the command was retried) were ignored and the node was left deactivated.
    For example, if a new catalog were submitted, but couldn't be processed
    because the database was temporarily down, and the node was deactivated
    before the catalog was retried, the catalog would be dropped. Now the
    catalog will be stored, though the node will stay deactivated. Commands
    *received* after a node is deactivated will continue to reactivate the node
    as before.

1.0.5
-----

Many thanks to the following people who contributed patches to this
release:

* Chris Price
* Deepak Giridharagopal

Fixes:

* Drop a large, unused index on catalog_resources(tags)

    This index was superseded by a GIN index on the same column, but the previous
    index was kept around by mistake. This should result in a space savings of
    10-20%, as well as a possible very minor improvement in catalog insert
    performance.

1.0.4
-----

Many thanks to the following people who contributed patches to this
release:

* Chris Price

Fixes:

* (#16554) Fix postgres query for numeric comparisons

    This commit changes the regex that we are using for numeric
    comparisons in postgres to a format that is compatible with both 8.4
    and 9.1.

1.0.3
-----

NOTE: This version was not officially released, as additional fixes
came in between the time we tagged this and the time we were going to
publish release artifacts.

Many thanks to the following people who contributed patches to this
release:

* Deepak Giridharagopal
* Nick Lewis
* Chris Price

Fixes:

* (#17216) Fix problems with UTF-8 transcoding

    Certain 5 and 6 byte sequences were being incorrectly transcoded to
    UTF-8 on Ruby 1.8.x systems. We now do two separate passes, one with
    iconv and one with our hand-rolled transcoding algorithms. Better
    safe than sorry!

* (#17498) Pretty-print JSON HTTP responses

    We now output more nicely-formatted JSON when using the PuppetDB
    HTTP API.

* (#17397) DB pool setup fails with numeric username or password

    This bug happens during construction of the DB connection pool. If
    the username or password is numeric, when parsing the configuration
    file they're turned into numbers. When we go to actually create the
    pool, we get an error because we're passing in numbers when strings
    are expected.

* (#17524) Better logging and response handling for version checks

    Errors when using the `version` endpoint are now caught, logged at a
    more appropriate log level, and a reasonable HTTP response code is
    returned to callers.


1.0.2
-----

Many thanks to the following people who contributed patches to this
release:

* Matthaus Owens

Fixes:

* (#17178) Update rubylib on debian/ubuntu installs

    Previously the terminus would be installed to the 1.8 sitelibdir for ruby1.8 or
    the 1.9.1 vendorlibdir on ruby1.9. The ruby1.9 code path was never used, so
    platforms with ruby1.9 as the default (such as quantal and wheezy) would not be
    able to load the terminus. Modern debian packages put version agnostic ruby
    code in vendordir (/usr/lib/ruby/vendor_ruby), so this commit moves the
    terminus install dir to be vendordir.

1.0.1
-----

Many thanks to the following people who contributed patches to this
release:

* Deepak Giridharagopal
* Nick Lewis
* Matthaus Litteken
* Chris Price

Fixes:

* (#16180) Properly handle edges between exported resources

    This was previously failing when an edge referred to an exported
    resource which was also collected, because it was incorrectly
    assuming collected resources would always be marked as NOT
    exported. However, in the case of a node collecting a resource which
    it also exports, the resource is still marked exported. In that
    case, it can be distinguished from a purely exported resource by
    whether it's virtual. Purely virtual, non-exported resources never
    appear in the catalog.

    Virtual, exported resources are not collected, whereas non-virtual,
    exported resources are. The former will eventually be removed from
    the catalog before being sent to the agent, and thus aren't eligible
    for participation in a relationship. We now check whether the
    resource is virtual rather than exported, for correct behavior.

* (#16535) Properly find edges that point at an exec by an alias

    During namevar aliasing, we end up changing the :alias parameter to
    'alias' and using that for the duration (to distinguish "our"
    aliases form the "original" aliases). However, in the case of exec,
    we were bailing out early because execs aren't isomorphic, and not
    adding 'alias'. Now we will always change :alias to 'alias', and
    just won't add the namevar alias for execs.

* (#16407) Handle trailing slashes when creating edges for file
    resources

    We were failing to create relationships (edges) to File resources if
    the relationship was specified with a different number of trailing
    slashes in the title than the title of the original resource.

* (#16652) Replace dir with specific files for terminus package

    Previously, the files section claimed ownership of Puppet's libdir,
    which confuses rpm when both packages are installed. This commit
    breaks out all of the files and only owns one directory, which
    clearly belongs to puppetdb. This will allow rpm to correctly
    identify files which belong to puppet vs puppetdb-terminus.


1.0.0
-----

The 1.0.0 release contains no changes from 0.11.0 except a minor packaging change.

0.11.0
-----

Many thanks to the following people who contributed patches to this
release:

* Kushal Pisavadia
* Deepak Giridharagopal
* Nick Lewis
* Moses Mendoza
* Chris Price

Notable features:

* Additional database indexes for improved performance

    Queries involving resources (type,title) or tags without much
    additional filtering criteria are now much faster. Note that tag
    queries cannot be sped up on PostgreSQL 8.1, as it doesn't have
    support for GIN indexes on array columns.

* Automatic generation of heap snapshots on OutOfMemoryError

    In the unfortunate situation where PuppetDB runs out of memory, a
    heap snapshot is automatically generated and saved in the log
    directory. This helps us work with users to much more precisely
    triangulate what's taking up the majority of the available heap
    without having to work to reproduce the problem on a completely
    different system (an often difficult proposition). This helps
    keep PuppetDB lean for everyone.

* Preliminary packaging support for Fedora 17 and Ruby 1.9

    This hasn't been fully tested, nor integrated into our CI systems,
    and therefore should be considered experimental. This fix adds
    support for packaging for ruby 1.9 by modifying the @plibdir path
    based on the ruby version. `RUBY_VER` can be passed in as an
    environment variable, and if none is passed, `RUBY_VER` defaults to
    the ruby on the local host as reported by facter. As is currently
    the case, we use the sitelibdir in ruby 1.8, and with this commit
    use vendorlibdir for 1.9. Fedora 17 ships with 1.9, so we use this
    to test for 1.9 in the spec file. Fedora 17 also ships with open-jdk
    1.7, so this commit updates the Requires to 1.7 for fedora 17.

* Resource tags semantics now match those of Puppet proper

    In Puppet, tags are lower-case only. We now fail incoming catalogs that
    contain mixed case tags, and we treat tags in queries as
    case-insensitive comparisons.

Notable fixes:

* Properly escape resource query strings in our terminus

    This fixes failures caused by storeconfigs queries that involve, for
    example, resource titles whose names contain spaces.

* (#15947) Allow comments in puppetdb.conf

    We now support whole-line comments in puppetdb.conf.

* (#15903) Detect invalid UTF-8 multi-byte sequences

    Prior to this fix, certain sequences of bytes used on certain
    versions of Puppet with certain versions of Ruby would cause our
    terminii to send malformed data to PuppetDB (which the daemon then
    properly rejects with a checksum error, so no data corruption would
    have taken place).

* Don't remove puppetdb user during RPM package uninstall

    We never did this on Debian systems, and most other packages seem
    not to as well. Also, removing the user and not all files owned by
    it can cause problems if another service later usurps the user id.

* Compatibility with legacy storeconfigs behavior for duplicate
    resources

    Prior to this commit, the puppetdb resource terminus was not setting
    a value for "collector_id" on collected resources.  This field is
    used by puppet to detect duplicate resources (exported by multiple
    nodes) and will cause a run to fail. Hence, the semantics around
    duplicate resources were ill-specified and could cause
    problems. This fix adds code to set the collector id based on node
    name + resource title + resource type, and adds tests to verify that
    a puppet run will fail if it collects duplicate instances of the
    same resource from different exporters.

* Internal benchmarking suite fully functional again

    Previous changes had broken the benchmark tool; functionality has
    been restored.

* Better version display

    We now display the latest version info during daemon startup and on the
    web dashboard.

0.10.0
-----

Many thanks to the following people who contributed patches to this
release:

* Deepak Giridharagopal
* Nick Lewis
* Matthaus Litteken
* Moses Mendoza
* Chris Price

Notable features:

* Auto-deactivation of stale nodes

    There is a new, optional setting you can add to the `[database]`
    section of your configuration: `node-ttl-days`, which defines how
    long, in days, a node can continue without seeing new activity (new
    catalogs, new facts, etc) before it's automatically deactivated
    during a garbage-collection run.

    The default behavior, if that config setting is ommitted, is the
    same as in previous releases: no automatic deactivation of anything.

    This feature is useful for those who have a non-trivial amount of
    volatility in the lifecycles of their nodes, such as those who
    regularly bring up nodes in a cloud environment and tear them down
    shortly thereafter.

* (#15696) Limit the number of results returned from a resource query

    For sites with tens or even hundreds of thousands of resources, an
    errant query could result in PuppetDB attempting to pull in a large
    number of resources and parameters into memory before serializing
    them over the wire. This can potentially trigger out-of-memory
    conditions.

    There is a new, optional setting you can add to the `[database]`
    section of your configuration: `resource-query-limit`, which denotes
    the maximum number of resources returnable via a resource query. If
    the supplied query results in more than the indicated number of
    resources, we return an HTTP 500.

    The default behavior is to limit resource queries to 20,000
    resources.

* (#15696) Slow query logging

    There is a new, optional setting you can add to the `[database]`
    section of your configuration: `log-slow-statements`, which denotes
    how many seconds a database query can take before the query is
    logged at WARN level.

    The default behavior for this setting is to log queries that take more than 10
    seconds.

* Add support for a --debug flag, and a debug-oriented startup script

    This commit adds support for a new command-line flag: --debug.  For
    now, this flag only affects logging: it forces a console logger and
    ensures that the log level is set to DEBUG. The option is also
    added to the main config hash so that it can potentially be used for
    other purposes in the future.

    This commit also adds a shell script, `puppetdb-foreground`, which
    can be used to launch the services from the command line. This
    script will be packaged (in /usr/sbin) along with the
    puppetdb-ssl-setup script, and may be useful in helping users
    troubleshoot problems on their systems (especially problems with
    daemon startup).

Notable fixes:

* Update CONTRIBUTING.md to better reflect reality

    The process previously described in CONTRIBUTING.md was largely
    vestigial; we've now updated that documentation to reflect the
    actual, current contribution process.

* Proper handling of composite namevars

    Normally, as part of converting a catalog to the PuppetDB wire
    format, we ensure that every resource has its namevar as one of its
    aliases. This allows us to handle edges that refer to said resource
    using its namevar instead of its title.

    However, Puppet implements `#namevar` for resources with composite
    namevars in a strange way, only returning part of the composite
    name. This can result in bugs in the generated catalog, where we
    may have 2 resources with the same alias (because `#namevar` returns
    the same thing for both of them).

    Because resources with composite namevars can't be referred to by
    anything other than their title when declaring relationships,
    there's no real point to adding their aliases in anyways. So now we
    don't bother.

* Fix deb packaging so that the puppetdb service is restarted during
    upgrades

    Prior to this commit, when you ran a debian package upgrade, the
    puppetdb service would be stopped but would not be restarted.

* (#1406) Add curl-based query examples to docs

    The repo now contains examples of querying PuppetDB via curl over
    both HTTP and HTTPS.

* Documentation on how to configure PuppetDB to work with "puppet apply"

    There are some extra steps necessary to get PuppetDB working
    properly with Puppet apply, and there are limitations
    thereafter. The repo now contains documentation around what those
    limitations are, and what additional configuration is necessary.

* Upgraded testing during acceptance test runs

    We now automatically test upgrades from the last published version
    of PuppetDB to the currently-under-test version.

* (#15281) Added postgres support to acceptance testing

    Our acceptance tests now regularly run against both the embedded
    database and PostgreSQL, automatically, on every commit.

* (#15378) Improved behavior of acceptance tests in single-node
    environment

    We have some acceptance tests that require multiple nodes in order
    to execute successfully (mostly around exporting / collecting
    resources). If you tried to run them in a single-node environment,
    they would give a weird ruby error about 'nil' not defining a
    certain method. Now, they will be skipped if you are running without
    more than one host in your acceptance test-bed.

* Spec tests now work against Puppet master branch

    We now regularly and automatically run PuppetDB spec tests against
    Puppet's master branch.

* Acceptance testing for RPM-based systems

    Previously we were running all of our acceptance tests solely
    against Debian systems. We now run them all, automatically upon each
    commit against RedHat machines as well.

* Added new `rake version` task

    Does what it says on the tin.
