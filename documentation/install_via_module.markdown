---
layout: default
title: "PuppetDB 1.1 » Installing PuppetDB Via Module"
canonical: "/puppetdb/1.1/install_via_module.html"
---

[module]: http://forge.puppetlabs.com/puppetlabs/puppetdb
[config_with_module]: ./configure.html#playing-nice-with-the-puppetdb-module

You can install and configure all of PuppetDB's components and prerequisites (including PuppetDB itself, PostgreSQL, firewall rules on RedHat-like systems, and the
terminus plugins for your Puppet master) using [the PuppetDB module][module] from the Puppet Forge. This is the **easiest method** for installing PuppetDB. 


Step 1: Enable the Puppet Labs Package Repository
-----

If you haven't already, you will need to do **one** of the following: 

* Enable the Puppet Labs package repository on your PuppetDB server and puppet master server.
* Grab the PuppetDB and terminus plugin packages, and import them into your site's local package repos. 

To enable the Puppet Labs repos, follow the instructions linked below:

- [Instructions for PE users](/guides/puppetlabs_package_repositories.html#puppet-enterprise-repositories)
- [Instructions for open source users](/guides/puppetlabs_package_repositories.html#open-source-repositories)

Step 2: Assign Classes to Nodes
-----

Using the normal methods for your site, assign the PuppetDB module's classes to your servers. You have three main options for deploying PuppetDB:

* If you are installing PuppetDB on the same server as your puppet master, assign the `puppetdb`  and `puppetdb::master::config` classes to it.
* If you want to run PuppetDB on its own server with a local PostgreSQL instance, assign the `puppetdb` class to it, and assign the `puppetdb::master::config` class to your puppet master. Make sure to set the class parameters as necessary.
* If you want PuppetDB and PostgreSQL to each run on their own servers, assign the `puppetdb::server` class and the `puppetdb::database::postgresql` classes to different servers, and the `puppetdb::master::config` class to your puppet master. Make sure to set the class parameters as necessary.

These classes automatically configure most aspects of PuppetDB. If you need to set additional settings (to change the `node_ttl`, for example), see [the "Playing Nice With the PuppetDB Module" section][config_with_module] of the "Configuring" page. 

For full details on how to use the module, see the [README_GETTING_STARTED.md
file](https://github.com/puppetlabs/puppetlabs-puppetdb/blob/master/README_GETTING_STARTED.md) in the module's GitHub repo.  The module also includes some sample manifests in
the `tests` directory that demonstrate its basic usage.
