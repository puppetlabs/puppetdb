---
title: "Community projects and add-ons"
layout: default
canonical: "/puppetdb/latest/community_add_ons.html"
---
# Community add-ons

**None of the following projects are published by or endorsed by Puppet.** They are linked to here for informational purposes only.

[nagios]: https://github.com/jasonhancock/nagios-puppetdb
[dashboard]: ./maintain_and_tune.html#monitor-the-performance-dashboard
[query]: https://github.com/dalen/puppet-puppetdbquery
[exports]: http://forge.puppetlabs.com/zack/exports
[exported]: https://puppet.com/docs/puppet/latest/lang_exported.html
[cms-puppetdb-tools]: https://github.com/tskirvin/cms-puppetdb-tools

## [Jason Hancock --- nagios-puppetdb][nagios]

[A collection of Nagios scripts/plugins for monitoring PuppetDB.][nagios] These plugins get data using [PuppetDB's metrics APIs](./api/metrics/v1/mbeans.html). Pulling this data into Nagios lets you monitor key metrics over time and receive alerts when they cross certain thresholds. This can partially or completely replace the [built-in performance dashboard][dashboard]. Especially useful for knowing when the heap size or thread count needs tuning.

## [Erik Dalén --- PuppetDB query functions for Puppet][query]

[A Puppet module with functions for querying PuppetDB data.][query] By default, [exported resources][exported] are the only way for Puppet manifests to get other nodes' data from PuppetDB. These functions let you get other data. In particular, the `pdbnodequery` function can let you search nodes by class or resource, an operation that normally requires multiple PuppetDB queries. The functions in this module include:

* `pdbresourcequery`
* `pdbnodequery`
* `pdbfactquery`
* `pdbstatusquery`
* `pdbquery`

## [Zack Smith --- Puppet face for querying PuppetDB exports][exports]

[A Puppet module with a face querying PuppetDB for exported resources.][exports] This simple face can be used for listing exported resources from the command line. It has support for querying all exports or filtering to a comma-separated list. Example syntax:

* `puppet node exports`
* `puppet node exports file`
* `puppet node exports file,user`

## [Tim Skirvin --- cms-puppetdb-tools (PuppetDB CLI tools)][cms-puppetdb-tools]

[A set of command-line scripts for querying PuppetDB.][cms-puppetdb-tools] This was originally written for use in a specific Puppet environment, but includes a few scripts that are more generally useful. This includes:

* `puppetdb-tangled` Lists "tangled" host, or where the most recent report was a success in which something changed (an event status changed), and some number of these changes have occurred several times in the last several runs (something is changing back and forth over and over again).
* `puppetdb-failed` Lists hosts that failed in their last Puppet run.
* `puppetdb-tooquiet` Lists hosts that have not checked in over the last two hours (configurable).

There are also a variety of tools for querying specific system facts and providing useful reports, which are tied to the upstream environment fact list.
