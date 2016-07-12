---
title: "PuppetDB 4.1: Installing PuppetDB from source"
layout: default
---

[leiningen]: https://github.com/technomancy/leiningen#installation
[configure_postgres]: ./configure.html#using-postgresql
[configure_heap]: ./configure.html#configuring-the-java-heap-size
[module]: ./install_via_module.html
[packages]: ./install_from_packages.html
[running_tests]: /CONTRIBUTING.html#running-the-tests

> **Note:** If you are running Puppet Enterprise version 3.0 or later, you do
> not need to install PuppetDB, as it is already installed as part of PE.

This page describes how to install PuppetDB from source code, and how to run
PuppetDB directly from source without installing.

If possible, we recommend installing PuppetDB
[with the puppetlabs-puppetdb module][module] or [from packages][packages];
either approach will be easier than installing from source. However, if you are
testing a new version, developing PuppetDB, or installing it on a system not
supported with official packages, you will need to install PuppetDB from source.

Step 1: Installation prerequisites
-----

Use your system's package tools to ensure that the following prerequisites are installed:

* (Optional) Puppet Server 2.x or higher
* A working Puppet agent or server setup (for ssl-setup to succeed)
* Facter, version 3 or higher
* JDK 1.7 or higher
* [Leiningen][]
* Git (for checking out the source code)
* Rake (version 0.9.6 or higher)

Step 2, option A: Install from source
-----

Install Leiningen:

    $ mkdir ~/bin && cd ~/bin
    $ curl -L 'https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein' -o lein --tlsv1
    $ chmod ugo+x lein
    $ ./lein
    # symlink lein to somewhere in your $PATH
    $ sudo ln -s /full/path/to/bin/lein /usr/local/bin

Run the following commands to build a distribution tarball from source:

    $ mkdir -p ~/git && cd ~/git
    $ git clone git://github.com/puppetlabs/puppetdb
    $ git checkout stable    # Or a particular tag you wish to install
    $ cd puppetdb
    $ lein install
    $ lein with-profile ezbake ezbake stage
    $ cd target/staging
    $ rake package:bootstrap
    $ rake package:tar

Now unpack the tarball to prepare for installation:

    $ cd pkg
    $ tar -xzf puppetdb-*.tar.gz
    $ cd puppetdb-*

To perform a full installation of the service and the PuppetDB-termini code
(usually the best choice when running PuppetDB on the same host as the Puppet
Server):

    $ sudo bash install.sh all

Otherwise, for service only:

    $ sudo bash install.sh service

Or for terminus code only:

    $ sudo bash install.sh termini

Step 2, option B: Run directly from source
-----

While installing from source is useful for simply running a development version
for testing, for development it's better to be able to run **directly** from
source, without any installation step.

Run the following commands:

    $ mkdir -p ~/git && cd ~/git
    $ git clone git://github.com/puppetlabs/puppetdb
    $ cd puppetdb

    # Download the dependencies
    $ lein deps

This will let you develop on PuppetDB and see your changes by simply editing the
code and restarting the server. It will not create an init script or default
configuration directory. To start the PuppetDB service when running from source,
you will need to run the following:

    $ lein run services -c /path/to/config.ini

A sample config file is provided in the root of the source repo:
`config.sample.ini`. You can also provide a conf.d-style directory instead of a
flat config file.

Next, you will need to [setup some test users](running_tests) to run the tests locally

Step 3: Configure a database
-----

In most cases you should
[set up a PostgreSQL server and configure PuppetDB to use it][configure_postgres].
You may also need to [adjust the maximum heap size][configure_heap].

You can change PuppetDB's database at any time while the service is shut down,
but note that changing the database does not migrate PuppetDB's data, so the new
database will be empty.

Step 4: Start the PuppetDB service
-----

If you _installed_ PuppetDB from source, you can start PuppetDB by running the
following:

    $ sudo service puppetdb start

And if Puppet is installed, you can permanently enable PuppetDB by running:

    $ sudo puppet resource service puppetdb ensure=running enable=true

If you are running PuppetDB from source, you should start it as follows:

    # From the directory in which PuppetDB's source is stored:
    $ lein run services -c /path/to/config.ini

> PuppetDB is now fully functional and ready to receive catalogs and facts from
> any number of puppet master servers.

Finish: Connect Puppet to PuppetDB
-----

[You should now configure your puppet master(s) to connect to PuppetDB](./connect_puppet_master.html).

If you use a standalone Puppet site,
[you should configure every node to connect to PuppetDB](./connect_puppet_apply.html).
