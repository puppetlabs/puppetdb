---
title: "PuppetDB 3.2 » Installing PuppetDB from Source"
layout: default
canonical: "/puppetdb/latest/install_from_source.html"
---

[perf_dashboard]: ./maintain_and_tune.html#monitor-the-performance-dashboard
[leiningen]: https://github.com/technomancy/leiningen#installation
[configure_postgres]: ./configure.html#using-postgresql
[configure_heap]: ./configure.html#configuring-the-java-heap-size
[module]: ./install_via_module.html
[packages]: ./install_from_packages.html
[migrating]: ./migrate.html

> **Note:** If you are running Puppet Enterprise 3.0 or later, PuppetDB is already installed as part of PE. You do not need to install it separately.

This page describes how to install PuppetDB from an archive of the source code, or alternately how to run it directly from source without installing.

If possible, we recommend installing PuppetDB [with the puppetlabs-puppetdb module][module] or [from packages][packages]; either approach will be easier. However, if you are testing a new version, developing PuppetDB, or installing it on a system not supported with official packages, you will need to install it from source.

Step 1: Install Prerequisites
-----

Use your system's package tools to ensure that the following prerequisites are installed:

* Facter, version 1.7.0 or higher
* JDK 1.7 or higher
* [Leiningen][]
* Git (for checking out the source code)


Step 2, Option A: Install from Source
-----

Install Leiningen:

    $ mkdir ~/bin && cd ~/bin
    $ curl -L 'https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein' -o lein --tlsv1
    $ chmod ugo+x lein
    $ ./lein
    # symlink lein to somewhere in your $PATH
    $ sudo ln -s /full/path/to/bin/lein /usr/local/bin

Run the following commands:

> **Note:**
>
> If you are using Puppet 4 or greater, the `rake` command below should
> be replaced by the `rake` version supplied by Puppet at
> `/opt/puppetlabs/puppet/bin/rake`

    $ mkdir -p ~/git && cd ~/git
    $ git clone git://github.com/puppetlabs/puppetdb
    $ cd puppetdb
    $ rake package:bootstrap
    $ sudo LEIN_ROOT=true rake install

This will install PuppetDB, putting a `puppetdb` init script in `/etc/init.d` on
[sysvinit](http://en.wikipedia.org/wiki/Init#SysV-style) based systems or in
`/etc/sysconfig` on [systemd](http://en.wikipedia.org/wiki/Systemd) based systems.
This also creates a default configuration directory in `/etc/puppetdb`.

The puppetdb service is set to run as the puppetdb user. You will need to add
this user to your system.

Create a `puppetdb` user and group:

    $ sudo groupadd puppetdb
    $ sudo useradd puppetdb -g puppetdb

Step 2, Option B: Run Directly from Source
-----

While installing from source is useful for simply running a development version
for testing, for development it's better to be able to run *directly* from
source, without any installation step.

Run the following commands:

    $ mkdir -p ~/git && cd ~/git
    $ git clone git://github.com/puppetlabs/puppetdb
    $ cd puppetdb

    # Download the dependencies
    $ lein deps

This will let you develop on PuppetDB and see your changes by simply editing the code and restarting the server. It will not create an init script or default configuration directory. To start the PuppetDB service when running from source, you will need to run the following:

    $ lein run services -c /path/to/config.ini

A sample config file is provided in the root of the source repo:  `config.sample.ini`. You can also provide a conf.d-style directory instead of a flat config file.

Other useful commands for developers:

* `lein test` to run the test suite

Step 3, Option A: Run the SSL Configuration Script
-----

If your PuppetDB server has puppet agent installed, has received a valid certificate from your site's Puppet CA, and you _installed_ PuppetDB from source, then PuppetDB can re-use Puppet's certificate.

Run the following command:

    $ sudo /usr/sbin/puppetdb ssl-setup

This will copy the relevant PEM files from your Puppet installation into `/etc/puppetdb/ssl` and can be used to correct your SSL configuration in `jetty.ini` to use those files.

You should now configure HTTPS in PuppetDB's config file(s); [see below](#step-4-configure-https).

Step 3, Option B: Manually Generating and Preparing Certificates
-----

If you will not be using Puppet on your PuppetDB server, you must manually create a certificate, and copy the relevant files into place. This is more of an involved process, so we highly recommend installing Puppet and using Option A above, even if you will not be using puppet agent to manage the PuppetDB server.

### On the CA Puppet Master: Create a Certificate

Use `puppet cert generate` to create a certificate and private key for your PuppetDB server. Run the following, using your PuppetDB server's hostname:

    $ sudo puppet cert generate puppetdb.example.com

### Copy the Certificate to the PuppetDB Server

Copy the CA certificate, the PuppetDB certificate, and the PuppetDB private key to your PuppetDB server. Run the following on your CA puppet master server, using your PuppetDB server's hostname:

    $ sudo scp $(puppet master --configprint ssldir)/ca/ca_crt.pem puppetdb.example.com:/etc/puppetdb/ssl/ca.pem
    $ sudo scp $(puppet master --configprint ssldir)/private_keys/puppetdb.example.com.pem puppetdb.example.com:/etc/puppetdb/ssl/private.pem
    $ sudo scp $(puppet master --configprint ssldir)/certs/puppetdb.example.com.pem puppetdb.example.com:/etc/puppetdb/ssl/public.pem

You may now log out of your puppet master server.

### Correct permissions

On your PuppetDB, ensure the certificates have the correct permissions:

    $ sudo chown puppetdb:puppetdb /etc/puppetdb/ssl/*.pem
    $ sudo chmod 0600 /etc/puppetdb/ssl/*.pem

You should now configure HTTPS in PuppetDB's config file(s); [see below](#step-4-configure-https).

Step 4: Configure HTTPS
-----

In your PuppetDB configuration file(s), edit the `[jetty]` section. If you installed from source, edit `/etc/puppetdb/conf.d/jetty.ini`; if you are running from source, edit the config file you chose.

The `[jetty]` section should contain the following, with your PuppetDB server's hostname and desired ports:

    [jetty]
    # Optional settings:
    host = puppetdb.example.com
    port = 8080
    # Required settings:
    ssl-host = puppetdb.example.com
    ssl-port = 8081
    ssl-cert = /etc/puppetdb/ssl/public.pem
    ssl-key = /etc/puppetdb/ssl/private.pem
    ssl-ca-cert = /etc/puppetdb/ssl/ca.pem

If you don't want to do unsecured HTTP at all, you can omit the `host` and `port` settings. However, this may limit your ability to use PuppetDB for other purposes, including viewing its [performance dashboard][perf_dashboard]. A reasonable compromise is to set `host` to `localhost`, so that unsecured traffic is only allowed from the local box; tunnels can then be used to gain access to the performance dashboard.

Step 5: Configure Database
-----

If this is a production deployment, you should confirm and configure your database settings:

- Deployments of **100 nodes or fewer** can continue to use the default built-in database backend, but should [increase PuppetDB's maximum heap size][configure_heap] to at least 1 GB.
- Large deployments should [set up a PostgreSQL server and configure PuppetDB to use it][configure_postgres]. You may also need to [adjust the maximum heap size][configure_heap].

You can change PuppetDB's database at any time, but note that changing the database does not migrate PuppetDB's data, so the new database will be empty. However, as this data is automatically generated many times a day, PuppetDB should recover in a relatively short period of time.


Set `puppetdb` ownership on puppetdb system files:

    $ sudo chown -R puppetdb:puppetdb /etc/puppetdb
    $ sudo chown -R puppetdb:puppetdb /var/lib/puppetdb

Step 6: Start the PuppetDB Service
-----

If you _installed_ PuppetDB from source, you can start PuppetDB by running the following:

    $ sudo service puppetdb start

And if Puppet is installed, you can permanently enable PuppetDB by running:

    $ sudo puppet resource service puppetdb ensure=running enable=true

If you are running PuppetDB from source, you should start it as follows:

    # From the directory in which PuppetDB's source is stored:
    $ lein run services -c /path/to/config.ini

> PuppetDB is now fully functional and ready to receive catalogs and facts from any number of puppet master servers.

Finish: Connect Puppet to PuppetDB
-----

[You should now configure your puppet master(s) to connect to PuppetDB](./connect_puppet_master.html).

If you use a standalone Puppet site, [you should configure every node to connect to PuppetDB](./connect_puppet_apply.html).
