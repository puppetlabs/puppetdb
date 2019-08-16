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

## PuppetDB 6.5.0

### New features and improvements

- **New experimental `catalog-input-contents` endpoint.** Use the [`catalog-input-contents`](./api/query/v4/catalog_input_contents.markdown) endpoint to query for the most recent
catalog inputs that PuppetDB has received for any nodes. ([PDB-4371](https://tickets.puppetlabs.com/browse/PDB-4371)

- **Submit `inputs` to a catalog.** PuppetDB can now optionally store "inputs", such as Hiera keys, during catalog compilation. See the [command's wire format](puppet.com/docs/puppetdb/latest/api/wire_format/catalog_inputs_format_v1.md) for more information on how to submit them. [PDB-4372](https://tickets.puppetlabs.com/browse/PDB-4372)

### Bug fixes

 - We've updated the default auto-vacuum settings for several tables which PuppetDB was vacuuming more frequently than neccessary. These changes will apply once at the next upgrade. [PDB-3745](https://tickets.puppetlabs.com/browse/PDB-3745)