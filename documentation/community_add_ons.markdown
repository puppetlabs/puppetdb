---
title: "PuppetDB 3.1 » Community Projects and Add-ons"
layout: default
canonical: "/puppetdb/latest/community_add_ons.html"
---


**None of the following projects are published by or endorsed by Puppet Labs.** They are linked to here for informational purposes only.

[nagios]: https://github.com/jasonhancock/nagios-puppetdb
[dashboard]: ./maintain_and_tune.html#monitor-the-performance-dashboard
[query]: https://github.com/dalen/puppet-puppetdbquery
[exports]: http://forge.puppetlabs.com/zack/exports
[exported]: /puppet/latest/reference/lang_exported.html

[Jason Hancock --- nagios-puppetdb][nagios]
-----

[A collection of Nagios scripts/plugins for monitoring PuppetDB.][nagios] These plugins get data using [PuppetDB's metrics APIs](./api/query/v1/metrics.html). Pulling this data into Nagios lets you monitor key metrics over time and receive alerts when they cross certain thresholds. This can partially or completely replace the [built-in performance dashboard][dashboard]. Especially useful for knowing when the heap size or thread count needs tuning.


[Erik Dalén --- PuppetDB query functions for Puppet][query]
-----

[A Puppet module with functions for querying PuppetDB data.][query] By default, [exported resources][exported] are the only way for Puppet manifests to get other nodes' data from PuppetDB. These functions let you get other data. In particular, the `pdbnodequery` function can let you search nodes by class or resource, an operation that normally requires multiple PuppetDB queries. The functions in this module include:

* `pdbresourcequery`
* `pdbnodequery`
* `pdbfactquery`
* `pdbstatusquery`
* `pdbquery`

[Zack Smith --- Puppet face for querying puppetdb exports][exports]
-----

[A Puppet module with a face querying PuppetDB for exported resources.][exports] This simple face can be used for listing exported resources from the command line. It has support for querying all exports or filtering to a comma seperated list. Example syntax:

* `puppet node exports`
* `puppet node exports file`
* `puppet node exports file,user`
