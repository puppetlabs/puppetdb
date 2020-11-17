---
title: "Installing from packages"
layout: default
---

[connect_master]: ./connect_puppet_server.html
[connect_apply]: ./connect_puppet_apply.html
[ssl_script]: ./maintain_and_tune.html#redo-ssl-setup-after-changing-certificates
[configure_postgres]: ./configure.html#using-postgresql
[configure_heap]: ./configure.html#configuring-the-java-heap-size
[configure_jetty]: ./configure.html#jetty-http-settings
[requirements]: ./index.html#standard-install-rhel-centos-debian-and-ubuntu
[install_module]: ./install_via_module.html
[module]: http://forge.puppet.com/puppetlabs/puppetdb
[keystore_instructions]: ./postgres_ssl.html

> **Note:** If you are running Puppet Enterprise version 3.0 or later, you do
> not need to install PuppetDB, as it is already installed as part of PE.

This page describes how to manually install and configure PuppetDB
from the official packages. Users are encouraged to install PuppetDB
via the [PuppetDB module][module] instead of installing the packages
directly. Using the module for setting up PuppetDB is much easier and
less error prone. See [Installing PuppetDB via Puppet
module][install_module] for more info.

Additionally, these instructions may be useful for understanding PuppetDB's
various moving parts, and can be helpful if you need to create your own PuppetDB
module.

> **Notes:**
>
> * After following these instructions, you must
>   [connect your Puppet master(s) to PuppetDB][connect_master]. (If you use a
>   standalone Puppet deployment, you will need to
>   [connect every node to PuppetDB][connect_apply].)
> * These instructions are for
>   [platforms with official PuppetDB packages][requirements]. To install on
>   other systems, follow
>   [our instructions for installing from source](./install_from_source.html).
> * If this is a production deployment,
>   [review the scaling recommendations](./scaling_recommendations.html) before
>   installing. You should ensure that your PuppetDB server will be able to
>   comfortably handle your site's load.

Platform specific install notes
-----

**Ubuntu 18.04**
* Enable the [universe repository](https://help.ubuntu.com/community/Repositories/Ubuntu), which contains packages necessary for PuppetDB
* Ensure Java 8 is installed

Step 1: Install and configure Puppet
-----

If Puppet isn't fully installed and configured on your PuppetDB server,
[install it][installpuppet] and request/sign/retrieve a certificate for the
node.

[installpuppet]: {{puppet}}/install_pre.html

Your PuppetDB server should be running Puppet agent and have a signed
certificate from your Puppet master server. If you run `puppet agent --test`, it
should successfully complete a run, ending with `Notice: Applied catalog in X.XX
seconds`.

> Note: If Puppet doesn't have a valid certificate when PuppetDB is installed,
> you will have to
> [run the SSL config script and edit the config file][ssl_script], or
> [manually configure PuppetDB's SSL credentials][keystore_instructions] before
> the Puppet master will be able to connect to PuppetDB.

Step 2: Enable the Puppet Platform package repository
-----

If you didn't already use it to install Puppet, you will need to
[enable the Puppet Platform package repository]({{puppet}}/puppet_platform.html)

Step 3: Install PuppetDB
-----

Use Puppet to install PuppetDB:

    $ sudo puppet resource package puppetdb ensure=latest

Step 4: Configure database
-----

- [Set up a PostgreSQL server and configure PuppetDB to use it][configure_postgres].

Step 5: Start the PuppetDB service
-----

Use Puppet to start the PuppetDB service and enable it on startup.

    $ sudo puppet resource service puppetdb ensure=running enable=true

You must also configure your PuppetDB server's firewall to accept incoming
connections on port 8081.

> PuppetDB is now fully functional and ready to receive facts, catalogs, and
> reports from any number of Puppet master servers.

Finish: Connect Puppet to PuppetDB
-----

[You should now configure your Puppet master(s) to connect to PuppetDB][connect_master].

If you use a standalone Puppet site,
[you should configure every node to connect to PuppetDB][connect_apply].

Troubleshooting installation problems
-----

* Check the log file (`/var/log/puppetlabs.puppetdb/puppetdb.log`), and see
  whether PuppetDB knows what the problem is.
* If PuppetDB is running but the Puppet master can't reach it, check
  [PuppetDB's `[jetty]` configuration][configure_jetty] to see which port(s) it
  is listening on, then attempt to reach it by Telnet (`telnet <HOST> <PORT>`)
  from the Puppet master server. If you can't connect, the firewall may be
  blocking connections. If you can, Puppet may be attempting to use the wrong
  port, or PuppetDB's keystore may be misconfigured (see below).
* Check whether any other service is using PuppetDB's port and interfering with
  traffic.
* Check [PuppetDB's `[jetty]` configuration][configure_jetty] and the
  `/etc/puppetlabs/puppetdb/ssl` directory, and make sure it has the necesary
  SSL files created. If it didn't create these during installation, you will
  need to [run the SSL config script and edit the config file][ssl_script]
  before a puppet master can contact PuppetDB.
