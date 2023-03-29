---
title: "PuppetDB release notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

[configure_postgres]: configure.markdown#using-postgresql
[kahadb_corruption]: /puppetdb/4.2/trouble_kahadb_corruption.html
[pg_trgm]: http://www.postgresql.org/docs/current/static/pgtrgm.html
[upgrading]: api/query/v4/upgrading-from-v3.markdown
[puppetdb-module]: https://forge.puppetlabs.com/puppetlabs/puppetdb
[migrate]: /puppetdb/3.2/migrate.html
[upgrades]: upgrade.markdown
[pqltutorial]: api/query/tutorial-pql.markdown
[stockpile]: https://github.com/puppetlabs/stockpile
[queue_support_guide]: pdb_support_guide.markdown#message-queue
[upgrade_policy]: versioning_policy.markdown#upgrades

---

## PuppetDB 6.0.4

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

Austin Blatt, Chris Roddy, Erik Hansen, Ethan J. Brown, Heston Hoffman,
Jean Bond, Rob Browning, Robert Roland, Wyatt Alt, and Zak Kent

## PuppetDB 6.0.3

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

## PuppetDB 6.0.2

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

Austin Blatt, Brandon High, Claire Cadman, David Lutterkort, Erik
Dalén, Ethan J. Brown, Heston Hoffman, Molly Waggett, Morgan Rhodes,
Nate Wolfe, Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 6.0.1

PuppetDB 6.0.1 is a new feature and bug-fix release.

### New features

- Added support for fact blacklist regexes. Omits facts whose name completely matched any of the expressions provided. Added a "facts-blacklist-type" database configuration option which defaults to "literal", producing the existing behavior, but can be set to "regex" to indicate that the facts-blacklist items are java patterns. [PDB-3928](https://tickets.puppetlabs.com/browse/PDB-3928)
- Currently, when building and testing from source, internal Puppet repositories are consulted by default, which may cause the process to take longer than necessary. Until the defaults are changed, the delays can be avoided by setting `PUPPET_SUPPRESS_INTERNAL_LEIN_REPOS=true` in the environment. [PDB-4135](https://tickets.puppetlabs.com/browse/PDB-4135)

### Bug fixes 

- PuppetDB should interpret JDK version numbers correctly when evaluating compatibility at startup. Previously, it was not accounting for the switch from the "1.10..." to 10..." format in newer versions of the JDK. [PDB-4141](https://tickets.puppetlabs.com/browse/PDB-4141)

### Contributors

Austin Blatt, Claire Cadman, Ethan J. Brown, Michelle Fredette, Morgan
Rhodes, Rob Browning, Robert Roland, and Zak Kent

## PuppetDB 6.0.0

### New features

- Puppet 6 supports 
[rich_data](https://github.com/puppetlabs/puppet-specifications/blob/master/language/types_values_variables.md#richdata) 
types like Timestamp and SemVer, and enables rich data by default. 
When rich data is enabled, readable string representations of rich 
data values may appear in the report resource event `old_value` and 
`new_value` fields, and in catalog parameter values. ([PDB-4082](https://tickets.puppetlabs.com/browse/PDB-4082))
- A `help` subcommand has been added to display usage information to standard output. If an invalid command is specified, usage information will now be printed to standard error, not standard output. ([PDB-3993](https://tickets.puppetlabs.com/browse/PDB-3993))
- PuppetDB has migrated to Clojure 1.9. ([PDB-3953])(https://tickets.puppetlabs.com/browse/PDB-3953))

### Upgrading

-  Support for ActiveMQ has been completely removed, meaning that
   PuppetDB can no longer convert an existing queue to the new format
   when upgrading from versions older than 4.3.0, but since PuppetDB's
   [upgrade policy][upgrade_policy] forbids skipping major versions,
   this should not present a problem since any version 5 release will
   perform the conversion at startup.

   As a result of the removal, these ActiveMQ specific configuration
   options have been retired: `store-usage`, `temp-usage`,
   `memory-usage`, and `max-frame-size`

### Bug fixes

- Prior to this fix, the HTTP submission with `command_broadcast` enabled 
always returned the last response. As a result, a failure was shown if 
the last connection produced a 503 response even though there was 
previously a successful PuppetDB response and the minimum successful 
responses had been met. This issue does not occur with responses that 
raised an exception. Since the puppet `http_pool` does not raise 503 
as an exception, this issue can be seen when PuppetDB is in 
maintenance mode. This fix changes the behavior to send the last successful response 
when the minimum successful submissions have been met. ([PDB-4020](https://tickets.puppetlabs.com/browse/PDB-4020))
- A problem that could cause harmless but noisy database connection errors during shutdown has been fixed. ([PDB-3952](https://tickets.puppetlabs.com/browse/PDB-3952))
- If using the default logback.xml configuration, PuppetDB should notice log config file changes every 60 seconds. Recent versions of PuppetDB had stopped noticing as a result of changes to Trapperkeeper (TK-426). This is fixed. ([PDB-3884](https://tickets.puppetlabs.com/browse/PDB-3884))
- PuppetDB now no longer attempts database migrations at startup under inappropriate conditions, for example when the relevant migrations table is unreadable. ([PDB-3268](https://tickets.puppetlabs.com/browse/PDB-3268))

### Deprecations

- PuppetDB no longer officially supports JDK 7. PuppetDB 6.0.0 officially supports JDK 8, and has been tested against JDK 10. Please see the [FAQ](puppetdb-faq.markdown#which-versions-of-java-are-supported) for further, or more current information. ([PDB-4069](https://tickets.puppetlabs.com/browse/PDB-4069))
- Support for these database configuration options has been completely retired: `classname`, `subprotocol`, `log-slow-statements`, and `conn-keep-alive`. Aside from warning at startup, PuppetDB will completely ignore them, and references to them have been removed from the documentation. ([PDB-3935](https://tickets.puppetlabs.com/browse/PDB-3935))

### Contributors

Austin Blatt, Charlie Sharpsteen, Garrett Guillotte, Morgan Rhodes, Rob
Browning, and Zak Kent
