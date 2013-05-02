---
title: "PuppetDB 1.3 » Using PuppetDB"
layout: default
canonical: "/puppetdb/latest/using.html"
---

[exported]: /puppet/2.7/reference/lang_exported.html


Currently, the main use for PuppetDB is to enable advanced features in Puppet. We expect additional applications to be built on PuppetDB as it becomes more widespread.

If you wish to build applications on PuppetDB, see the navigation sidebar for links to the API spec. 

Checking Node Status
-----

The PuppetDB plugins [installed on your puppet master(s)](./connect_puppet_master.html) include a `status` action for the `node` face. On your puppet master, run:

    $ sudo puppet node status <node> 

where `<node>` is the name of the node you wish to investigate. This will tell you whether the node is active, when its last catalog was submitted, and when its last facts were submitted. 

Using Exported Resources
-----

PuppetDB lets you use exported resources, which allows your nodes to publish information for use by other nodes. 

[See here for more about using exported resources.][exported]

Using the Inventory Service
-----

PuppetDB provides better performance for Puppet's inventory service.

[See here for more about using the inventory service and building applications on it.](/guides/inventory_service.html) If you are using Puppet Enterprise's console, or Puppet Dashboard with inventory support turned on, you will not need to change your configuration --- PuppetDB will become the source of inventory information as soon as [the puppet master is connected to it](./connect_puppet_master.html).
