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
