---
title: "PuppetDB: Upgrading PuppetDB"
layout: default
---


[dashboard]: ./maintain_and_tune.html#monitor-the-performance-dashboard
[connect_master]: ./connect_puppet_master.html
[connect_apply]: ./connect_puppet_apply.html
[tracker]: https://tickets.puppetlabs.com/browse/PDB
[start_source]: ./install_from_source.html#step-4-start-the-puppetdb-service
[plugin_source]: ./connect_puppet_master.html#on-platforms-without-packages
[module]: ./install_via_module.html
[puppetdb3]: /puppetdb/3.2/migrate.html
[versioning]: ./versioning_policy.html#upgrades

Checking for updates
-----

PuppetDB's [performance dashboard][dashboard] displays the current version in
the upper right corner. It also automatically checks for updates and will show a
link to the newest version under the version indicator if your deployment is out
of date.

Migrating existing data
-----

If you are not planning to change your underlying PuppetDB database
configuration prior to upgrading, you don't need to worry about migrating your
existing data: PuppetDB will handle this automatically. However, if you plan to
switch to a different database, you should export your existing data prior to
changing your database configuration, but you must use PuppetDB 3.x to do so.
Please consult the [Migrating Data][puppetdb3] for more information.

Upgrading with the PuppetDB module
-----

If you [installed PuppetDB with the module][module], you only need to do the
following to upgrade:

1. Make sure that the Puppet master has an updated version of the
   [puppetlabs-puppetdb](https://forge.puppetlabs.com/puppetlabs/puppetdb)
   module installed.
2. If you imported the official packages into your local package repositories,
   import the new versions of the PuppetDB and PuppetDB-termini packages.
3. Change the value of the `puppetdb_version` parameter for the `puppetdb` or
   `puppetdb::server` and `puppetdb::master::config` classes, unless it was set
   to `latest`.
4. If you are doing a large version jump, trigger a Puppet run on the PuppetDB
   server before the Puppet master server has a chance to do a Puppet run. (It's
   possible for a new version of the PuppetDB-termini to use API commands
   unsupported by old PuppetDB versions, which would cause Puppet failures until
   PuppetDB was upgraded, but this should be very rare.)

Manually upgrading PuppetDB
-----

### What to upgrade

When a new version of PuppetDB is released, you will need to upgrade:

1. PuppetDB itself
2. The [PuppetDB-termini][connect_master] on every Puppet master (or
   [every node][connect_apply], if using a standalone deployment).

You should **upgrade PuppetDB first.** Because PuppetDB will be down for a few
minutes during the upgrade and Puppet masters will not be able to serve catalogs
until it comes back, you should schedule upgrades during a maintenance window
during which no new nodes will be brought online.

If you upgrade PuppetDB without upgrading the PuppetDB-termini, your Puppet
deployment should continue to function identically, with no loss of
functionality. However, you may not be able to take advantage of new PuppetDB
features until you upgrade the PuppetDB-termini.

### Upgrading PuppetDB

**On your PuppetDB server:** stop the PuppetDB service, upgrade the PuppetDB
package, then restart the PuppetDB service.

    $ sudo puppet resource service puppetdb ensure=stopped
    $ sudo puppet resource package puppetdb ensure=latest
    $ sudo puppet resource service puppetdb ensure=running

#### On platforms without packages

If you installed PuppetDB by running `rake install`, you should obtain a fresh
copy of the source, stop the service, and run `rake install` again. Note that
this workflow is not well tested; if you run into problems, please report them
on the [PuppetDB issue tracker][tracker].

If you are running PuppetDB from source, you should stop the service, replace
the source, and
[start the service as described in the advanced installation guide][start_source].

### Upgrading the terminus plugins

**On your Puppet master servers:** upgrade the PuppetDB-termini package, then
restart the Puppet master's web server:

    $ sudo puppet resource package puppetdb-termini ensure=latest

The command to restart the Puppet master will vary, depending on which web
server you are using.

#### On platforms without packages

Obtain a fresh copy of the PuppetDB source, and follow
[the instructions for installing the termini][plugin_source].

The command to restart the Puppet master will vary, depending on which web
server you are using.

### Upgrading across multiple major versions

As stated by the [versioning policy][versioning], you cannot "skip"
major versions of PuppetDB when upgrading.  For example, if you need
to upgrade from PuppetDB 4.2.3 to 6.0.0, you must run some version of
PuppetDB 5 at least long enough for it to upgrade your existing data.

The upgrade subcommand can help with this.  When specified, PuppetDB
will quit as soon as it has finished all of the necessary work:

    $ puppetdb upgrade -c /path/to/config.ini
