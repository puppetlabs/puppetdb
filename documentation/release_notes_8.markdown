---
title: "PuppetDB: Release notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

[query-timeout-parameter]: ./api/query/v4/overview.markdown#url-parameters

---

# PuppetDB: Release notes

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
