---
title: "Installing PuppetDB via Puppet module"
layout: default
---

[module]: http://forge.puppet.com/puppetlabs/puppetdb
[config_with_module]: ./configure.html#playing-nice-with-the-puppetdb-module

You can install and configure all of PuppetDB's components and prerequisites
(including PuppetDB itself, PostgreSQL, firewall rules on RedHat-like systems,
and the PuppetDB-termini for your Puppet master) using
[the PuppetDB module][module] from the Puppet Forge.

* If you are **already familiar with Puppet** and have a working Puppet
  deployment, this is the easiest method for installing PuppetDB. In this guide,
  we expect that you already know how to assign Puppet classes to nodes.
* If you are **just getting started with Puppet,** you may find it easier to
  follow our guide to
  [installing PuppetDB from packages](./install_from_packages.html).

Step 1: Enable the Puppet Platform package repository
-----

If you haven't done so already, you will need to do **one** of the following:

* [Enable the Puppet Platform package repository]({{puppet}}/puppet_platform.html)
  on your PuppetDB server and Puppet master server.
* If you don't use the Puppet Platform repository, make the PuppetDB and
  PuppetDB-terminus packages available via your alternate installation strategy.
  For the module install to succeed a command like `yum install puppetdb`, or the
  equivalent one that uses your system's package manager, needs to be able to
  succeed.

Step 2: Assign classes to nodes
-----

Using the normal methods for your site, assign the PuppetDB module's classes to
your servers. You have three main options for deploying PuppetDB:

* If you are installing PuppetDB on the same server as your Puppet master,
  assign the `puppetdb` and `puppetdb::master::config` classes to it.
* If you want to run PuppetDB on its own server with a local PostgreSQL
  instance, assign the `puppetdb` class to it, and assign the
  `puppetdb::master::config` class to your Puppet master. Make sure to set the
  class parameters as necessary.
* If you want PuppetDB and PostgreSQL to each run on their own servers, assign
  the `puppetdb::server` class and the `puppetdb::database::postgresql` classes
  to different servers, and the `puppetdb::master::config` class to your Puppet
  master. Make sure to set the class parameters as necessary.

Note: By default, the module sets up the PuppetDB dashboard to be accessible
only via `localhost`. If you'd like to allow access to the PuppetDB dashboard
via an external network interface, set the `listen_address` parameter on either
of the `puppetdb` or `puppetdb::server` classes as follows:

    class { 'puppetdb':
        listen_address => 'example.foo.com'
    }

These classes automatically configure most aspects of PuppetDB. If you need to
adjust additional settings (to change the `node_ttl`, for example), see
[the "Playing nice with the PuppetDB module" section][config_with_module] of the
"Configuring PuppetDB" page.

For full details on how to use the module, see the
[PuppetDB module documentation][module]
on Puppet Forge. The module also includes some sample manifests in the `tests`
directory that demonstrate its basic usage.
