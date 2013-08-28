---
title: "PuppetDB 1.3 » Installing PuppetDB from Source"
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

This page describes how to install PuppetDB from an archive of the source code, or alternately how to run it directly from source without installing.

If possible, we recommend installing PuppetDB [with the puppetlabs-puppetdb module][module] or [from packages][packages]; either approach will be easier. However, if you are testing a new version, developing PuppetDB, or installing it on a system not supported with official packages, you will need to install it from source. 

> **Note:**
>
> If you'd like to migrate existing exported resources from your ActiveRecord storeconfigs database, please see the documentation on [Migrating Data][migrating].


Step 1: Install Prerequisites
-----

Use your system's package tools to ensure that the following prerequisites are installed:

* Facter, version 1.6.8 or higher <!-- TODO find the actual required version. Problem was a rake aborted! undefined method `downcase' for nil:NilClass error. -->
* JDK 1.6 or higher
* [Leiningen][]
* Git (for checking out the source code)


Step 2, Option A: Install from Source
-----

Run the following commands:

    $ mkdir -p ~/git && cd ~/git
    $ git clone git://github.com/puppetlabs/puppetdb
    $ cd puppetdb
    $ sudo rake install

This will install PuppetDB, put a `puppetdb` init script in `/etc/init.d` and create a default configuration directory in `/etc/puppetdb`.

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

    $ sudo /usr/sbin/puppetdb-ssl-setup

This will create a keystore and truststore in `/etc/puppetdb/ssl` and will print the password to both files in `/etc/puppetdb/ssl/puppetdb_keystore_pw.txt`. 

You should now configure HTTPS in PuppetDB's config file(s); [see below](#step-4-configure-https).

Step 3, Option B: Manually Create a Keystore and Truststore
-----

If you will not be using Puppet on your PuppetDB server, you must manually create a certificate, a keystore, and a truststore. This is an involved process, so we highly recommend installing Puppet and using Option A above, even if you will not be using puppet agent to manage the PuppetDB server.

### On the CA Puppet Master: Create a Certificate

Use `puppet cert generate` to create a certificate and private key for your PuppetDB server. Run the following, using your PuppetDB server's hostname:

    $ sudo puppet cert generate puppetdb.example.com


### Copy the Certificate to the PuppetDB Server

Copy the CA certificate, the PuppetDB certificate, and the PuppetDB private key to your PuppetDB server. Run the following on your CA puppet master server, using your PuppetDB server's hostname:

    $ sudo scp $(puppet master --configprint ssldir)/ca/ca_crt.pem puppetdb.example.com:/tmp/certs/ca_crt.pem
    $ sudo scp $(puppet master --configprint ssldir)/private_keys/puppetdb.example.com.pem puppetdb.example.com:/tmp/certs/privkey.pem
    $ sudo scp $(puppet master --configprint ssldir)/certs/puppetdb.example.com.pem puppetdb.example.com:/tmp/certs/pubkey.pem

You may now log out of your puppet master server.

### On the PuppetDB Server: Create a Truststore

On your PuppetDB server, navigate to the directory where you copied the certificates and keys:

    $ cd /tmp/certs

Now use `keytool` to create a _truststore_ file. A _truststore_ contains the set of CA certs to use for validation.

    # keytool -import -alias "My CA" -file ca_crt.pem -keystore truststore.jks
    Enter keystore password:
    Re-enter new password:
    .
    .
    .
    Trust this certificate? [no]:  y
    Certificate was added to keystore

Note that you _must_ supply a password. Remember the password you
used, as you'll need it to configure PuppetDB later. Once imported,
you can view your certificate:

    # keytool -list -keystore truststore.jks
    Enter keystore password:

    Keystore type: JKS
    Keystore provider: SUN

    Your keystore contains 1 entry

    my ca, Mar 30, 2012, trustedCertEntry,
    Certificate fingerprint (MD5): 99:D3:28:6B:37:13:7A:A2:B8:73:75:4A:31:78:0B:68

Note the MD5 fingerprint; you can use it to verify this is the correct cert:

    # openssl x509 -in ca_crt.pem -fingerprint -md5
    MD5 Fingerprint=99:D3:28:6B:37:13:7A:A2:B8:73:75:4A:31:78:0B:68

### On the PuppetDB Server: Create a Keystore

In the same directory as the truststore you just created, use `keytool` to create a Java _keystore_.  A _keystore_ file contains
certificates to use during HTTPS.

    # cat privkey.pem pubkey.pem > temp.pem
    # openssl pkcs12 -export -in temp.pem -out puppetdb.p12 -name puppetdb.example.com
    Enter Export Password:
    Verifying - Enter Export Password:
    # keytool -importkeystore  -destkeystore keystore.jks -srckeystore puppetdb.p12 -srcstoretype PKCS12 -alias puppetdb.example.com
    Enter destination keystore password:
    Re-enter new password:
    Enter source keystore password:

You can validate this was correct:

    # keytool -list -keystore keystore.jks
    Enter keystore password:

    Keystore type: JKS
    Keystore provider: SUN

    Your keystore contains 1 entry

    puppetdb.example.com, Mar 30, 2012, PrivateKeyEntry,
    Certificate fingerprint (MD5): 7E:2A:B4:4D:1E:6D:D1:70:A9:E7:20:0D:9D:41:F3:B9

Compare to the certificate's fingerprint on the CA puppet master: 

    $ sudo puppet cert fingerprint puppetdb.example.com --digest=md5
    MD5 Fingerprint=7E:2A:B4:4D:1E:6D:D1:70:A9:E7:20:0D:9D:41:F3:B9

### On the PuppetDB Server: Move the Keystore and Truststore

Take the _truststore_ and _keystore_ you generated in the preceding
steps and copy them to a permanent home. These
instructions will assume you are using `/etc/puppetdb/ssl`.

Change the files' ownership to the user PuppetDB will run
as, and ensure that only that user can read the files:

    $ sudo chown puppetdb:puppetdb /etc/puppetdb/ssl/truststore.jks /etc/puppetdb/ssl/keystore.jks
    $ sudo chmod 400 /etc/puppetdb/ssl/truststore.jks /etc/puppetdb/ssl/keystore.jks

You can now safely delete the temporary copies of the keystore, truststore, CA certificate, PuppetDB certificate and private key. These can be retrieved or recreated using the original copies stored on the CA puppet master. 

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
    keystore = /etc/puppetdb/ssl/keystore.jks
    truststore = /etc/puppetdb/ssl/truststore.jks
    key-password = <password used when creating the keystore>
    trust-password = <password used when creating the truststore>

If you [ran the SSL configuration script](#step-3-option-a-run-the-ssl-configuration-script), the password will be in `/etc/puppetdb/ssl/puppetdb_keystore_pw.txt`. Use this for both the `key-password` and the `trust-password`.

If you don't want to do unsecured HTTP at all, you can omit the `host` and `port` settings. However, this may limit your ability to use PuppetDB for other purposes, including viewing its [performance dashboard][perf_dashboard]. A reasonable compromise is to set `host` to `localhost`, so that unsecured traffic is only allowed from the local box; tunnels can then be used to gain access to the performance dashboard.

Step 5: Configure Database
-----

If this is a production deployment, you should confirm and configure your database settings:

- Deployments of **100 nodes or fewer** can continue to use the default built-in database backend, but should [increase PuppetDB's maximum heap size][configure_heap] to at least 1 GB.
- Large deployments should [set up a PostgreSQL server and configure PuppetDB to use it][configure_postgres]. You may also need to [adjust the maximum heap size][configure_heap]. 

You can change PuppetDB's database at any time, but note that changing the database does not migrate PuppetDB's data, so the new database will be empty. However, as this data is automatically generated many times a day, PuppetDB should recover in a relatively short period of time. 



Step 6: Start the PuppetDB Service
-----

If you _installed_ PuppetDB from source, you can start PuppetDB by running the following:

    $ sudo /etc/init.d/puppetdb start

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
