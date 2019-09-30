---
title: "Known issues"
layout: default
canonical: "/puppetdb/latest/known_issues.html"
---


Bugs and feature requests
-----

[tracker]: https://tickets.puppetlabs.com/browse/PDB

PuppetDB's bugs and feature requests are managed in [Puppet's issue tracker][tracker]. Search this database if you're having problems and please report any new issues to us!

PuppetDB rejects Puppet Server SSL connections
-----

Starting in PuppetDB 6.6.0, PuppetDB can reject Puppet Server SSL connections due to a restricted set of cipher suites.
In an upcoming release, PuppetDB will be switched to default to the same cipher suites as Puppet Server.
In the interim, the workaround is to set the `cipher-suites` manually. See the ticket for the recommended set.
[PDB-4513](https://tickets.puppetlabs.com/browse/PDB-4513)

Hash projection has character limit of 63
-----

[PDB-2634](https://tickets.puppetlabs.com/browse/PDB-2634) Added support for using dot notation for projections.
This supports queries like the one below.
```
inventory[facts.os.family] {
  certname = "host-1"
}
```
The dotted hash projection `facts.os.family` must be 63, or fewer, characters. [PDB-4521](https://tickets.puppetlabs.com/browse/PDB-4521)

Broader issues
-----

### Autorequire relationships are opaque

Puppet resource types can "autorequire" other resources when certain conditions are met, but we don't correctly model these relationships in PuppetDB. (For example, if you manage two file resources where one is a parent directory of the other, Puppet will automatically make the child dependent on the parent.) The problem is that these dependencies are not written to the catalog; the Puppet agent creates these relationships on the fly when it reads the catalog. Getting these relationships into PuppetDB will require a significant change to Puppet's core.
