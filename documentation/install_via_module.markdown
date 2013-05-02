---
layout: default
title: "PuppetDB 1.3 » Installing PuppetDB Via Module"
canonical: "/puppetdb/latest/install_via_module.html"
---

[module]: http://forge.puppetlabs.com/puppetlabs/puppetdb
[config_with_module]: ./configure.html#playing-nice-with-the-puppetdb-module
[migrating]: ./migrate.html

You can install and configure all of PuppetDB's components and prerequisites (including PuppetDB itself, PostgreSQL, firewall rules on RedHat-like systems, and the
terminus plugins for your Puppet master) using [the PuppetDB module][module] from the Puppet Forge.

* If you are **already familiar with Puppet** and have a working Puppet deployment, this is the easiest method for installing PuppetDB. In this guide, we expect that you already know how to assign Puppet classes to nodes.
* If you are **just getting started with Puppet,** you should probably follow the [Installing PuppetDB From Packages guide](./install_from_packages.html) instead.

> **Note:**
>
> If you'd like to migrate existing exported resources from your ActiveRecord storeconfigs database, please see the documentation on [Migrating Data][migrating].

Step 1: Enable the Puppet Labs Package Repository
-----

If you haven't already, you will need to do **one** of the following:

* [Enable the Puppet Labs package repository](/guides/puppetlabs_package_repositories.html#open-source-repositories) on your PuppetDB server and puppet master server.
* Grab the PuppetDB and terminus plugin packages, and import them into your site's local package repos.

Step 2: Assign Classes to Nodes
-----

Using the normal methods for your site, assign the PuppetDB module's classes to your servers. You have three main options for deploying PuppetDB:

* If you are installing PuppetDB on the same server as your puppet master, assign the `puppetdb`  and `puppetdb::master::config` classes to it.
* If you want to run PuppetDB on its own server with a local PostgreSQL instance, assign the `puppetdb` class to it, and assign the `puppetdb::master::config` class to your puppet master. Make sure to set the class parameters as necessary.
* If you want PuppetDB and PostgreSQL to each run on their own servers, assign the `puppetdb::server` class and the `puppetdb::database::postgresql` classes to different servers, and the `puppetdb::master::config` class to your puppet master. Make sure to set the class parameters as necessary.

Note: by default the module sets up the PuppetDB dashboard to be accessible only via `localhost`.  If you'd like to allow access to the PuppetDB dashboard via an external network interface, you should set the `listen_address` parameter on either of the `puppetdb` or `puppetdb::server` classes.  e.g.:

    class { 'puppetdb':
        listen_address => 'example.foo.com'
    }

These classes automatically configure most aspects of PuppetDB. If you need to set additional settings (to change the `node_ttl`, for example), see [the "Playing Nice With the PuppetDB Module" section][config_with_module] of the "Configuring" page.

For full details on how to use the module, see the [PuppetDB module documentation](http://forge.puppetlabs.com/puppetlabs/puppetdb) on Puppet Forge.  The module also includes some sample manifests in the `tests` directory that demonstrate its basic usage.
