---
title: "PuppetDB: Release notes"
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
[puppet_apply]: ./connect_puppet_apply.html

## PuppetDB 6.7.3

This release includes various security improvements.

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
  - [Puppet Query Language (PQL) examples](./api/query/examples-pql.markdown) 
  - [AST query language (AST)](./api/query/v4/ast.markdown)

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

- **Submit `inputs` to a catalog.** PuppetDB can now optionally store "inputs", such as Hiera keys, during catalog compilation. See the [command's wire format](puppet.com/docs/puppetdb/latest/api/wire_format/catalog_inputs_format_v1.md) for more information on how to submit them. [PDB-4372](https://tickets.puppetlabs.com/browse/PDB-4372)

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
