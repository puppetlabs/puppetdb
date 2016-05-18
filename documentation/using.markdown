---
title: "PuppetDB 4.1 » Using PuppetDB"
layout: default
canonical: "/puppetdb/latest/using.html"
---

[exported]: /puppet/latest/reference/lang_exported.html


Currently, PuppetDB's primary use is enabling advanced Puppet features. As use becomes more widespread, we expect additional applications to be built on PuppetDB.

If you wish to build applications on PuppetDB, see the navigation sidebar for links to the API specifications.

Checking node status
-----

The PuppetDB plugins [installed on your Puppet master(s)](./connect_puppet_master.html) include a `status` action for the `node` face. On your Puppet master, run:

    $ sudo puppet node status <NODE>

where `<NODE>` is the name of the node you wish to investigate. This will tell you whether the node is active, when its last catalog was submitted, and when its last facts were submitted.

Using exported resources
-----

PuppetDB lets you use exported resources, which allows your nodes to publish information for use by other nodes.

[Learn more about using exported resources here.][exported]

