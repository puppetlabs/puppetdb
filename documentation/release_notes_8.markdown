---
title: "PuppetDB: Release notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

[benchmark]: ./load_testing_tool.markdown
[query-timeout-parameter]: ./api/query/v4/overview.markdown#url-parameters
[terminus-config]: ./puppetdb_connection.markdown

---

# PuppetDB: Release notes

## PuppetDB 8.4.1

Released February 27 2024

### Bug fixes

* Fixed an issue with negated regex queries (`!~`) on JSON fields (dotted fact
  paths and resource parameters), which were not matching the full negation of
  the regex match when the key was missing from some of the JSON maps.

### Contributors

Austin Blatt, Jonathan Newman, Josh Partlow, and Rob Browning

## PuppetDB 8.3.0

Released January 18 2024

### New features and improvements

* The PuppetDB terminus now supports the
  [`include_catalog_edges`][terminus-config] configuration option. Setting this
  value to false will omit all resource edges from the catalog submitted to
  PuppetDB.
  ([GitHub #3912](https://github.com/puppetlabs/puppetdb/issues/3912))

### Bug fixes

* PuppetDB queries should no longer risk hanging when run just after
  non-streaming queries (for example, those with `ast_only` set to
  true). ([GitHub #3933](https://github.com/puppetlabs/puppetdb/pull/3933))

### Contributors

Austin Blatt, Cas Donoghue, Eric Newton, Jonathan Newman, Josh Partlow,
Rob Browning, and Steve Axthelm

## PuppetDB 8.2.0

Released November 7 2023

### Security fixes

* Update trapperkeeper-webserver-jetty9 to 4.5.2 to address
  CVE-2023-44487, CVE-2023-36478, GHSA-58qw-p7qm-5rvh,
  GHSA-hmr7-m48g-48f6, and GHSA-3gh6-v5v9-6v9j.

* Update Bouncy Castle FIPS to v1.0.2.4 to resolve CVE-2022-45156
  and CVE-2023-33202.

### Bug fixes

* Update jvm-ssl-utils to 3.5.2 to address a stack overflow in
  certificates with tags.

### New features and improvements

* An `--offset` option has been added to the [`benchmark` command][benchmark]
  This allows you to run two or more Benchmark instances in parallel,
  offsetting the generated cert numbers so that the commands don't
  collide in the database.
  ([GitHub #3896](https://github.com/puppetlabs/puppetdb/issues/3896))

* The [`benchmark` command][benchmark] should be able to reach notably
  higher maximum output rates.  On one 60 core (non-hyperthreaded)
  host where previously it could only simulate about 80k nodes with a
  30 minute runinterval, it can now simulate over 140k nodes, more if
  the randomization percentage is reduced from 100.
  ([GitHub #3886](https://github.com/puppetlabs/puppetdb/issues/3886))
  (PDB-5712)

* The [`benchmark` command][benchmark]'s `-t`/`--threads` argument has
  been deprecated and renamed to `--senders`.
  ([GitHub #3886](https://github.com/puppetlabs/puppetdb/issues/3886))
  (PDB-5712)

* The [`benchmark` command][benchmark]'s default number of `--senders`
  has been changed from four times the host core (hyperthread) count
  to half the count (or 2, whichever's larger) after testing revealed
  that with a 60 core (non-hyperthreaded) host, only 16 senders were
  needed to hit a maximum rate with the local PuppetDB/PostgreSQL
  hosts.
  ([GitHub #3886](https://github.com/puppetlabs/puppetdb/issues/3886))
  (PDB-5712)

* A `--simulators` option has been added to the
  [`benchmark` command][benchmark].  It specifies the number of
  threads to use for the generation of new host commands and defaults
  to either 2, or half the core (hyperthread) count.  The previous
  internal value was always 4.
  ([GitHub #3886](https://github.com/puppetlabs/puppetdb/issues/3886))
  (PDB-5712)

* The [`benchmark` command][benchmark] command will now space out the
  factset, catalog, and report for each host more realistically.
  ([GitHub #3880](https://github.com/puppetlabs/puppetdb/pull/3880))
  (PDB-5691)

### Contributors

Austin Blatt, Nick Burgan-Illig, Joshua Partlow, and Rob Browning

## PuppetDB 8.1.1

Released September 14 2023

### Bug fixes

* PuppetDB should no longer throw a `CancelledKeyException` when a
  network connection is reused for multiple queries (originally
  noticed in the 8.1.0 release).
  ([GitHub #3866](https://github.com/puppetlabs/puppetdb/issues/3866))

### Contributors

Austin Blatt, Ingrida Cazers, Josh Partlow, Rob Browning, and
Steve Axthelm

## PuppetDB 8.1.0

Released August 22 2023

### New features and improvements

* RedHat Enterprise Linux 9 (RHEL 9) has been added as a supported
  platform.
  ([PDB-5667](https://perforce.atlassian.net/browse/PDB-5667))

* Ubuntu 22.04 has been added as a supported platform.
  ([PDB-5636](https://perforce.atlassian.net/browse/PDB-5636))

* PuppetDB will now abandon queries more promptly when a client
  disconnects.  Previously an expensive query could continue running
  indefinitely.  The same mechanism should also help ensure query
  timeouts are immediately enforced.
  ([GitHub #3867](https://github.com/puppetlabs/puppetdb/issues/3867))

* When no database migrations are pending, PuppetDB will no longer
  disconnect clients on restart.
  ([PE-36120](https://perforce.atlassian.net/browse/PE-36120))

### Bug fixes

* Some PQL queries with numerous `or` clauses should no longer cause
  PuppetDB to run out of memory.  Previously they could allocate an
  exorbitant amount of RAM.
  ([GitHub #3874](https://github.com/puppetlabs/puppetdb/issues/3874))

### Known issues

* The mechanism used to abandon queries and enforce timeouts mentioned
  above may throw a `CancelledKeyException` when a network connection
  is reused for multiple queries.  For the time being, the problem can
  be addressed by setting the experimental environment variable
  `PDB_PROMPTLY_END_QUERIES` to `false`.  (This variable may be
  removed in a future release.)
  ([GitHub #3866](https://github.com/puppetlabs/puppetdb/issues/3866))

### Contributors

Austin Blatt, Nick Burgan-Illig, Jonathan Newman, Eric Newton, Joshua
Partlow, Steve Axthelm, and Rob Browning

## PuppetDB 8.0.1

Released June 14 2023

## New features and improvements

* All PQL statements that take longer than one second to parse will be
  logged.  Previously that was only the case when query logging was
  enabled.
  ([PDB-5642](https://tickets.puppetlabs.com/browse/PDB-5642))
  ([PDB-5260](https://tickets.puppetlabs.com/browse/PDB-5260))

* A new `generate` CLI subcommand has been added.  It can create a
  base sampling of catalog, fact and report files suitable for
  consumption by [`benchmark`][benchmark].
  ([PDB-5593](https://tickets.puppetlabs.com/browse/PDB-5593))

* PuppetDB sync (PE only) now uses the query timeouts introduced in
  [PDB-4937](https://tickets.puppetlabs.com/browse/PDB-4937) to
  further constrain sync operations to run within the
  `entity-time-limit`.
  ([PDB-5232](https://tickets.puppetlabs.com/browse/PDB-5232))

### Contributors

Austin Blatt, Josh Partlow, and Rob Browning

## PuppetDB 8.0.0

Released April 25 2023

## New features and improvements

* Drop joins are now applied when evaluating sub-queries which should result in
  performance improvements.
  ([PDB-5557](https://tickets.puppetlabs.com/browse/PDB-5557))
* PuppetDB now supports query timeouts for queries to the `query/` endpoint via
  an [optional query parameter][query-timeout-parameter]. A
  [default](./configure.markdown#query-timeout-default) and a
  [maximum](./configure.markdown#query-timeout-max) can also be specified in
  the configuration. The current default is ten minutes.
  ([PDB-4937](https://tickets.puppetlabs.com/browse/PDB-4937))

## Removals

* The deprecated query streaming method has been removed, along with the
  associated `PDB_USE_DEPRECATED_STREAMING_METHOD` environment variable.

* The previously optional PostgreSQL trigram index support,
  [`pg_trgm`](https://www.postgresql.org/docs/14/pgtrgm.html) is now required.

### Upgrading

* PuppetDB requires Java 11+ and recommends Java 17. Our packages have
  been updated to require Java 11 or Java 17. **RedHat users** should be aware
  that Java 8 remains the highest priority Java on its distributions even when
  Java 11 is installed. This will cause PuppetDB to fail to start. Changing the
  default Java from 8 to 11 by installing Java 11 and selecting it as the
  default via `alternatives --config java` before upgrading will ensure a
  successful upgrade. The alternatives command can be used to rectify a failed
  PuppetDB 8 upgrade, you will then need to start the service manually.

* PostgreSQL 11, 12, and 13 are no longer supported. PostgreSQL 14+ is
  required. This is not enforced by PuppetDB, so you _can_ continue to use
  those unsupported PostgreSQL versions for now, but we reserve the right to
  change that in any future PuppetDB release. Please use this extra overlap
  time to upgrade your database.

### Contributors

Arthur Lawson, Austin Blatt, Ben Ford, Cas Donoghue, Jonathan Newman,
Josh Partlow, Nick Burgan-Illig, Nick Lewis, Noah Fontes, and Rob
Browning
