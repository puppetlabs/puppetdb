---
title: "PuppetDB 1.3 » Installing PuppetDB From Packages"
layout: default
canonical: "/puppetdb/latest/install_from_packages.html"
---

[connect_master]: ./connect_puppet_master.html
[connect_apply]: ./connect_puppet_apply.html
[keystore_instructions]: ./install_from_source.html#step-3-option-b-manually-create-a-keystore-and-truststore
[ssl_script]: ./install_from_source.html#step-3-option-a-run-the-ssl-configuration-script
[configure_postgres]: ./configure.html#using-postgresql
[configure_heap]: ./configure.html#configuring-the-java-heap-size
[configure_jetty]: ./configure.html#jetty-http-settings
[requirements]: ./index.html#standard-install-rhel-centos-debian-ubuntu-or-fedora
[module]: ./install_via_module.html
[migrating]: ./migrate.html

This page describes how to manually install and configure PuppetDB from the official packages.

* If you are **just getting started with Puppet** and don't yet know how to assign Puppet classes to nodes, this is the guide for you.
* If you are **already familiar with Puppet** and have a working Puppet deployment, we recommend that you [use the puppetlabs-puppetdb module][module] instead. See [the "Install via Module" page][module] for more details.

Additionally, these instructions may be useful for understanding the various moving parts, or in cases where you must create your own PuppetDB module.

> **Notes:**
>
> * If you'd like to migrate existing exported resources from your ActiveRecord storeconfigs database, please see the documentation on [Migrating Data][migrating].
> * After following these instructions, you should [connect your puppet master(s) to PuppetDB][connect_master]. (If you use a standalone Puppet deployment, you will need to [connect every node to PuppetDB][connect_apply].)
> * These instructions are for [platforms with official PuppetDB packages][requirements]. To install on other systems, you should instead follow [the instructions for installing from source](./install_from_source.html).
> * If this is a production deployment, [review the scaling recommendations](./scaling_recommendations.html) before installing. You should ensure your PuppetDB server will be able to comfortably handle your site's load.

Step 1: Install and Configure Puppet
-----
If Puppet isn't fully installed and configured yet on your PuppetDB server, [install it][installpuppet] and request/sign/retrieve a certificate for the node.

[installpuppet]: /guides/installation.html

Your PuppetDB server should be running puppet agent and have a signed certificate from your puppet master server. If you run `puppet agent --test`, it should successfully complete a run, ending with "`notice: Finished catalog run in X.XX seconds`."


> Note: If Puppet doesn't have a valid certificate when PuppetDB is installed, you will have to [run the SSL config script and edit the config file][ssl_script], or [manually configure PuppetDB's SSL credentials][keystore_instructions] before the puppet master will be able to connect to PuppetDB.

Step 2: Enable the Puppet Labs Package Repository
-----

If you didn't already use it to install Puppet, you will need to [enable the Puppet Labs package repository](/guides/puppetlabs_package_repositories.html#open-source-repositories) for your system.


Step 3: Install PuppetDB
-----

Use Puppet to install PuppetDB:

    $ sudo puppet resource package puppetdb ensure=latest


Step 4: Configure Database
-----

**If this is a production deployment,** you should confirm and configure your database settings:

- Deployments of **100 nodes or fewer** can continue to use the default built-in database backend, but should [increase PuppetDB's maximum heap size][configure_heap] to at least 1 GB.
- Large deployments over 100 nodes should [set up a PostgreSQL server and configure PuppetDB to use it][configure_postgres]. You may also need to [adjust the maximum heap size][configure_heap].

You can change PuppetDB's database at any time, but note that changing the database does not migrate PuppetDB's data, so the new database will be empty. However, as this data is automatically generated many times a day, PuppetDB should recover in a relatively short period of time.

Step 5: Start the PuppetDB Service
-----

Use Puppet to start the PuppetDB service and enable it on startup.

    $ sudo puppet resource service puppetdb ensure=running enable=true

You must also configure your PuppetDB server's firewall to accept incoming connections on port 8081.

> PuppetDB is now fully functional and ready to receive catalogs and facts from any number of puppet master servers.


Finish: Connect Puppet to PuppetDB
-----

[You should now configure your puppet master(s) to connect to PuppetDB][connect_master].

If you use a standalone Puppet site, [you should configure every node to connect to PuppetDB][connect_apply].

Troubleshooting Installation Problems
-----

* Check the log file, and see whether PuppetDB knows what the problem is. This file will be either `/var/log/puppetdb/puppetdb.log`.
* If PuppetDB is running but the puppet master can't reach it, check [PuppetDB's jetty configuration][configure_jetty] to see which port(s) it is listening on, then attempt to reach it by telnet (`telnet <host> <port>`) from the puppet master server. If you can't connect, the firewall may be blocking connections. If you can, Puppet may be attempting to use the wrong port, or PuppetDB's keystore may be misconfigured (see below).
* Check whether any other service is using PuppetDB's port and interfering with traffic.
* Check [PuppetDB's jetty configuration][configure_jetty] and the `/etc/puppetdb/ssl` directory, and make sure it has a truststore and keystore configured. If it didn't create these during installation, you will need to [run the SSL config script and edit the config file][ssl_script] or [manually configure a truststore and keystore][keystore_instructions] before a puppet master can contact PuppetDB.

