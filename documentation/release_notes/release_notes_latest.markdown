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


## PuppetDB 6.8.0

### New features and improvements

- **New `resource-events-ttl` configuration parameter.** Use the
  `resource-events-ttl` configuration parameter to automatically delete report events older
  than the specified time. The parameter rounds up to the nearest day.
  For example, `14h` rounds up to `1d`. For more information, see [Configuring
  PuppetDB](./configure.html#resource-events-ttl).
  [PDB-2487](https://tickets.puppetlabs.com/browse/PDB-2487)

- **New `delete` command.** Use the `delete` command to immediately delete the
  data associated with a certname. For more information, see [Commands
  endpoint](./api/admin/v1/cmd.html#delete-version-1). [PDB-3300](https://tickets.puppetlabs.com/browse/PDB-3300)    

### Bug fixes

- Resolved an issue where an unreachable
  PostgreSQL server could cause PuppetDB to exhaust its connection pool,
  requiring a restart.
  [PDB-4579](https://tickets.puppetlabs.com/browse/PDB-4579)

### Contributors