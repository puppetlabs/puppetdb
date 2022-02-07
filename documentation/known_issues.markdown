---
title: "Known issues"
layout: default
canonical: "/puppetdb/latest/known_issues.html"
---
# Known issues
## Bugs and feature requests

[tracker]: https://tickets.puppetlabs.com/browse/PDB

PuppetDB's bugs and feature requests are managed in [Puppet's issue tracker][tracker]. Search this database if you're having problems and please report any new issues to us!

## PuppetDB fact-contents queries take longer than usual

PuppetDB 6.20.0 and 7.9.0 contain [a
change](https://tickets.puppetlabs.com/browse/PDB-5259) to the fact-contents
query intended to reduce the reads required by Postgres, and improve
performance on larger datasets. The changes cause a performance regression when
used with PostgreSQL JIT compilation enabled. If you have JIT enabled, either
by setting it in PostgreSQL 11 or running the default settings on PostgreSQL
12+, you should disable it by setting `jit = off` in your `postgresql.conf`.
[PDB-5450](https://tickets.puppetlabs.com/browse/PDB-5450)

## PuppetDB returns an error from the status endpoint

In PuppetDB 6.11.0, 6.11.1, and 6.11.2, when PuppetDB cannot connect to the
database and a user queries the `/status/v1/services/puppetdb-status` endpoint
it would return an exception in the status response instead of reporting the
databases as down.  [PDB-4836](https://tickets.puppetlabs.com/browse/PDB-4836)

## PuppetDB rejects Puppet Server SSL connections

Starting in PuppetDB 6.6.0, PuppetDB can reject Puppet Server SSL connections due to a restricted set of cipher suites.
In an upcoming release, PuppetDB will be switched to default to the same cipher suites as Puppet Server.
In the interim, the workaround is to set the `cipher-suites` manually. See the ticket for the recommended set.
[PDB-4513](https://tickets.puppetlabs.com/browse/PDB-4513)

## Hash projection has character limit of 63

[PDB-2634](https://tickets.puppetlabs.com/browse/PDB-2634) Added support for using dot notation for projections.
This supports queries like the one below.
```
inventory[facts.os.family] {
  certname = "host-1"
}
```
The dotted hash projection `facts.os.family` must be 63, or fewer, characters. [PDB-4521](https://tickets.puppetlabs.com/browse/PDB-4521)

## Broader issues

### Autorequire relationships are opaque

Puppet resource types can "autorequire" other resources when certain conditions are met, but we don't correctly model these relationships in PuppetDB. (For example, if you manage two file resources where one is a parent directory of the other, Puppet will automatically make the child dependent on the parent.) The problem is that these dependencies are not written to the catalog; the Puppet agent creates these relationships on the fly when it reads the catalog. Getting these relationships into PuppetDB will require a significant change to Puppet's core.
