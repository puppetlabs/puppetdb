---
title: "PuppetDB: Release notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

[configure-postgres]: ./configure.markdown#using-postgresql
[drop-joins]: ./api/query/v4/query.markdown#experimental-query-optimization
[facts]: ./api/query/v4/facts.markdown
[api-overview]: ./api/query/v4/overview.markdown
[fact-contents]: ./api/query/v4/fact-contents.markdown
[known-issues]: ./known_issues.markdown

---

# PuppetDB: Release notes

## PuppetDB 7.15.0

Released November 7 2023

### Security fixes

* Update trapperkeeper-webserver-jetty9 to 4.5.2 to address
  CVE-2023-44487, CVE-2023-36478, GHSA-58qw-p7qm-5rvh,
  GHSA-hmr7-m48g-48f6, GHSA-3gh6-v5v9-6v9j

* Update Bouncy Castle FIPS to v1.0.2.4 to resolve CVE-2022-45156
  and CVE-2023-33202

### Bug fixes

* Update jvm-ssl-utils to 3.5.2 to address a stack overflow in
  certificates with tags.

## New features and improvements

* Allow for Puppet::Util::Puppetdb to flush config.
  ([PE-36947](https://perforce.atlassian.net/browse/PE-36947))

### Contributors

Austin Blatt, Nick Burgan-Illig, Joshua Partlow, and Rob Browning

## PuppetDB 7.14.0

Released August 22 2023

## New features and improvements

* RedHat Enterprise Linux 9 (RHEL 9) has been added as a supported
  platform. ([PDB-5671](https://perforce.atlassian.net/browse/PDB-5671))

* Ubuntu 22.04 has been added as a supported platform.
  ([PDB-5636](https://perforce.atlassian.net/browse/PDB-5636))

### Bug fixes

* Some PQL queries with numerous `or` clauses should no longer cause
  PuppetDB to run out of memory.  Previously they could allocate an
  exorbitant amount of RAM.
 ([GitHub #3874](https://github.com/puppetlabs/puppetdb/issues/3874))

### Contributors

Austin Blatt, Nick Burgan-Illig, Jonathan Newman, Eric Newton, Joshua
Partlow, Steve Axthelm, and Rob Browning

## PuppetDB 7.13.1

Released June 14 2023

## New features and improvements

* All PQL statements that take longer than one second to parse will be
  logged.  Previously that was only the case when query logging was
  enabled.
  ([PDB-5642](https://tickets.puppetlabs.com/browse/PDB-5642))
  ([PDB-5260](https://tickets.puppetlabs.com/browse/PDB-5260))

### Contributors

Austin Blatt, Nick Burgan-Illig, and Rob Browning

## PuppetDB 7.13.0

Released April 6 2023

### Bug fixes

* queries with thousands of `in array` entries would cause performance problems
  in query compilation.
  ([PDB-3171](https://tickets.puppetlabs.com/browse/PDB-3171))
* PuppetDB should no longer crash on reload (SIGHUP) in some cases
  (e.g. after startup but before processing any commands).
  ([PDB-5215](https://tickets.puppetlabs.com/browse/PDB-5215))

## New features and improvements

* PuppetDB installations with PostgreSQL 14+ will detach reports and resource_events
  partitions concurrently before dropping them.
  ([PDB-5554](https://tickets.puppetlabs.com/browse/PDB-5554))
* The reports and resource_events tables were migrated to use PostgreSQL declarative partitioning in support of PDB-5554.
  ([PDB-5567](https://tickets.puppetlabs.com/browse/PDB-5567))

### Contributors

Austin Blatt, Jonathan Newman, Joshua Partlow, Rob Browning, and Nick Lewis

## PuppetDB 7.12.1

Released January 17 2023

### Bug fixes

* Ordering query results by certain fields (e.g. the `trusted` facts
  field) should no longer cause an error.
  ([PDB-5568](https://tickets.puppetlabs.com/browse/PDB-5568))

* The `org.postgresql/postgresql` driver has been updated to version
  42.4.3 to address
  [CVE-2022-41946](https://nvd.nist.gov/vuln/detail/CVE-2022-41946),
  which is an information exposure vulnerability that could expose
  database information to a local system
  user. ([PDB-5570](https://tickets.puppetlabs.com/browse/PDB-5570))

* The `org.ini4j/ini4j` library has been upgraded to 0.5.4 to address
  [CVE-2022-41404](https://nvd.nist.gov/vuln/detail/CVE-2022-41404), a
  Denial of Service (DoS) vulnerability.
  ([PDB-5571](https://tickets.puppetlabs.com/browse/PDB-5571))

### Contributors

Austin Blatt and Rob Browning

## PuppetDB 7.12.0

Released December 6 2022

> Note: depending on your configuration, the bug fixes in this release
> may require attention.  See below.

### New features and improvements

* The timing of garbage collection operations can be controlled more
  selectively.  The `gc-interval` controls the timing of a set of
  operations, and now the timing of each of those operations can be
  [specified individually](./configure.markdown#database-settings).
  ([PDB-5547](https://tickets.puppetlabs.com/browse/PDB-5547))

### Bug fixes

* Queries should no longer be able to block report garbage collection
  indefinitely.  If you do not use the PostgreSQL module and have the
  recommended, separate `[read-database]` `username`, then you may
  need to make adjustments.

  Specifically, the the normal (write) `[database]` `username` must
  have the right to terminate the `[read-database]` `username`'s
  queries.  The [recommended configuration](./configure_postgres.markdown)
  has been updated to include a suitable `grant puppetdb_read to puppetdb`.
  ([PDB-5559](https://tickets.puppetlabs.com/browse/PDB-5559))

* The coordination of database migrations will now disallow
  `[read-database]` user connections as intended.  If you do not use
  the PostgreSQL module, have the recommended, separate
  `[read-database]` `username`, and have enabled migration
  coordination via a
  [`migrator-username`](./configure.markdown#migrator-username)
  then you may need to make adjustments.

  Specifically, the the normal `migrator-username` must have the
  ability to terminate the `[read-database]` `username`'s connections,
  which the [recommended configuration](./configure_postgres.markdown)
  accomplishes by granting the write user's role to the migrator via
  the `grant puppetdb to puppetdb_migrator`, allowing the migrator to
  terminate the read user's connections indirectly via the `grant
  puppetdb_read to puppetdb`, also in the recommended configuration.
  ([PDB-5559](https://tickets.puppetlabs.com/browse/PDB-5559))

* PuppetDB now drops expired partitions (e.g. reports) more
  effectively.  The changes will cause PostgreSQL to log messages like
  this: "FATAL: terminating connection due to administrator command".
  Previously PuppetDB could cause database deadlocks that might
  indefinitely prevent the expired partitions from being dropped.
  ([PDB-5559](https://tickets.puppetlabs.com/browse/PDB-5548))

### Contributors

April Murphy, Arthur Lawson, Austin Blatt, Cas Donoghue, Jonathan
Newman, Justin Stoller, Nick Lewis, and Rob Browning

## PuppetDB 7.11.2

Released October 11 2022

### New features and improvements

* PuppetDB will now log additional information when processing each
  command, including the producer timestamp, and when available, a
  prefix of the fingerprint.
  ([PDB-5524](https://tickets.puppetlabs.com/browse/PDB-5524))

### Contributors

Austin Blatt, Jonathan Newman, Justin Stoller, and Rob Browning

## PuppetDB 7.11.1

Released September 14 2022

### Security fixes

* The `org.postgresql/postgresql` driver has been updated to version
  42.4.1 to address
  [CVE-2022-31197](https://nvd.nist.gov/vuln/detail/CVE-2022-31197),
  which is an SQL injection risk that according to the CVE report, can
  only be exploited if an attacker controls the database to the extent
  that they can adjust relevant tables to have "malicious" column
  names. ([PE-34250](https://tickets.puppetlabs.com/browse/PE-34250))

### Contributors

April Murphy, Jonathan Newman, Rob Browning, and Stel Abrego

## PuppetDB 7.11.0

Released August 2 2022

### New features and improvements

* Query logging has been improved when `log-queries` is set to true. Now
  queries are logged with their UUID before they are parsed which makes
  debugging easier when there are PQL parsing issues.
  ([PDB-5482](https://tickets.puppetlabs.com/browse/PDB-5482))

### Bug fixes

* Fixed a bug that was introduced in 6.19.0 and 7.10.1 that caused valid
  queries using the ~> operator inside of an extract clause to fail. The patch
  also fixes a UX issue where providing the wrong amount of arguments in a ~>
  clause would result in a cryptic error.
  ([PE-33977](https://tickets.puppetlabs.com/browse/PE-33977))

* Fixed a bug that was introduced in 6.21.0 and 7.10.1 which caused upgrade
  failures with PostgreSQL hot standbys. The method of disabling the jit has
  been changed to avoid the problem.
  ([PDB-5483](https://tickets.puppetlabs.com/browse/PDB-5483))

### Contributors
Arjen Zonneveld, Austin Blatt, Jon-Paul Lindquist, Jonathan Newman, Katlin
Anderson, Maggie Dreyer, Rob Browning, and Stel Abrego

## PuppetDB 7.10.1

Released April 12 2022

### New features and improvements

* PuppetDB should require much less time and memory when parsing some
  PQL queries, , for example queries including many or clauses like
  `nodes {x or y or ...}`.  Previously 5000 clauses could not be
  parsed with an 8GB heap, and much smaller queries still required
  exorbitant amounts of memory and CPU time.
  ([PDB-5260](https://tickets.puppetlabs.com/browse/PDB-5260))

* Ubuntu 20.04, RedHat 8 (FIPS) (Puppet Enterprise only), and SUSE
  Linux Enterprise 15 are now supported.

### Contributors

Austin Blatt and Rob Browning

## PuppetDB 7.10.0

Released March 22 2022

### Improvements

* PuppetDB will no longer run a garbage collection on startup.  This
  may substantially reduce the time required before PuppetDB begins
  accepting commands and queries.
  ([PDB-5422](https://tickets.puppetlabs.com/browse/PDB-5422))

* The fact path GC now runs no more than once every 24 hours by
  default.  This should be much less expensive in most cases, in
  exchange for a potentially slower response to the disappearance of
  individual fact
  paths. ([PDB-5423](https://tickets.puppetlabs.com/browse/PDB-5423))

* PuppetDB will no longer process incoming commands during the initial
  sync.  This may allow the sync to finish more quickly, decreasing
  startup time (Puppet Enterprise only).
  ([PDB-5386](https://tickets.puppetlabs.com/browse/PDB-5386))

### Bug fixes

* PostgreSQL introduced a query JIT in version 11, and enabled it by
  default in 12.  At the moment, it causes some queries to be
  dramatically more expensive, and PuppetDB was affected, so it now
  disables the JIT for all of its
  queries. ([PDB-5452](https://tickets.puppetlabs.com/browse/PDB-5452))

### Contributors

Austin Blatt, Rob Browning, and Stel Abrego

## PuppetDB 7.9.2

Released February 9 2022

### Security fixes

This releases contains a fix for [CVE-2022-21724](https://nvd.nist.gov/vuln/detail/CVE-2022-21724) ([PDB-5449](https://tickets.puppetlabs.com/browse/PDB-5449)), a pgjdbc exploit. In order to use this exploit, an attacker must have permission to create JDBC connections via JDBC URL's while the application is running. Since PuppetDB only creates JDBC connections internally from configuration upon startup, this CVE is of very low risk to our users. In order for a PuppetDB user to be successfully attacked, the malicious actor would have to first acquire at least the user privileges of the PuppetDB user. Due to the CVE's high severity rating, we believe it's appropriate to release a fix regardless.

### Contributors

Austin Blatt, Rob Browning, and Stel Abrego

## PuppetDB 7.9.0

Released January 20 2022

### New features and improvements

* Added support for Debian 11. ([PDB-5390](https://tickets.puppetlabs.com/browse/PDB-5390))
* Improved performance of the "deactivate node" command. ([PDB-5378](https://tickets.puppetlabs.com/browse/PDB-5378))
* Improved performance of the fact-contents endpoint. Testing against a database of 10,000 mocked nodes, there was an observed 84% decrease in time taken to complete a difficult query. This optimization has a [known issue][known-issues] with PostgreSQL JIT compilation. ([PDB-5259](https://tickets.puppetlabs.com/browse/PDB-5259))

### Bug Fixes

* Fixed a bug with HA sync (Puppet Enterprise only) regarding `/pdb/query/v4/<entity>/<certname>` style queries that caused replicas to falsely report that the sync transferred 0 nodes. ([PDB-5381](https://tickets.puppetlabs.com/browse/PDB-5381))
* Fixed error handling issues in the command endpoint. Previously, providing a certname that was an empty string or `null` would cause PuppetDB to crash and prevent prior restarts from exiting maintenance mode. Upon other errors such as missing required parameters, the command endpoint would return a status 500 HTML page or cryptic internal error data. This patch ensures the command endpoint will always return a standard `{ "error": <description> }` JSON response upon any ingestion error and ingestion errors at the command endpoint will not cause PuppetDB to crash. ([PDB-5282](https://tickets.puppetlabs.com/browse/PDB-5282))

### Contributors

Austin Blatt, Rob Browning, and Stel Abrego

## PuppetDB 7.8.0

Released December 13 2021

### Bug fixes

PuppetDB should no longer delete the last day of reports a day earlier
than specified by `report-ttl` in some cases.
([PDB-5351](https://tickets.puppetlabs.com/browse/PDB-5351))

### Contributors

Austin Blatt, Rob Browning, and Stel Abrego

## PuppetDB 7.7.1

Released November 9 2021

### Security fixes

This release is part of both a Puppet Platform and PE release that resolves
CVEs, see [Puppet's CVE
announcements](https://puppet.com/security/cve/security-vulnerability-announcements)
for more information.

### Upgrade cautions

Puppet Agent 6.25.1 and 7.12.1
([PUP-11209](https://tickets.puppetlabs.com/browse/PUP-11209)) introduced a new
catalog resource field in order to resolve CVE-2021-27025. This field was not
handled properly by older versions of PuppetDB and will result in catalogs not
being stored in PuppetDB. Before upgrading _any_ agents in your installation to
6.25.1 or 7.12.1, you _must_ first upgrade your PuppetDB(s) to this version.
([PDB-5338](https://tickets.puppetlabs.com/browse/PDB-5338))

### Contributors

Austin Blatt, Bogdan Irimie, Rob Browning, Sebastian Miclea, and Stel Abrego

## PuppetDB 7.7.0

### Bug fixes

* If a query with an extract clause contains a misspelled option, the clause is completely ignored resulting in a misleading response body.
  ```
  ["from", "reports",
    ["extract", [["function", "count", "certname"]],
      ["null?", "type", false],
      ["groupy_by", "certname"]]]
  ```
  will return all the reports because the extract cause will be ignored ( it contains groupy_by instead of group_by).
  Instead of returning nil for a malformed extract clause (when converting the query to sql plan), try to identify the misspelled part and log an appropriate error message. ([PDB-4731](https://tickets.puppetlabs.com/browse/PDB-4731))


* When querying for trusted facts on inventory endpoint with a query like:
  ```
  inventory[] { trusted.extensions.foo = "bar"}
  ```
  instead of `facts.trusted.extensions.foo`, the index wasn't hit. The change introduced by this ticket ensures that an index is hit if the query is made with just `trusted.[fact]`. ([PDB-4985](https://tickets.puppetlabs.com/browse/PDB-4985))

### Contributors

Austin Blatt, Oana Tanasoiu, Rob Browning, and Sebastian Miclea

## PuppetDB 7.6.0

Released September 16 2021

### New features and improvements

* TLSv1.3 is enabled by default, it will be preferred over TLSv1.2 when
  possible. [PDB-5255](https://tickets.puppetlabs.com/browse/PDB-5255)
* Adds a new [optional query field][api-overview] `origin` that allows users
  initiating a query to identify their query, which will assist with debugging
  any query-related issues.
  [PDB-5216](https://tickets.puppetlabs.com/browse/PDB-5216)

## PuppetDB 7.5.2

Released August 17 2021

### Bug fixes

* Fixes multiple bug when grouping by a dotted fact path. For example,
  previously double quotes were required - now the must be omitted.
  [PDB-5214](https://tickets.puppetlabs.com/browse/PDB-5214)
  [PDB-4628](https://tickets.puppetlabs.com/browse/PDB-4628)

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

