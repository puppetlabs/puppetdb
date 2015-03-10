---
title: "PuppetDB 2.2 » Configuration"
layout: default
canonical: "/puppetdb/latest/configure.html"
---

[logback]: http://logback.qos.ch/manual/configuration.html
[dashboard]: ./maintain_and_tune.html#monitor-the-performance-dashboard
[repl]: ./repl.html
[pg_trgm]: http://www.postgresql.org/docs/current/static/pgtrgm.html
[postgres_ssl]: ./postgres_ssl.html
[module]: ./install_via_module.html
[low_catalog_dupe]: ./trouble_low_catalog_duplication.html
[puppetdb.conf]: ./connect_puppet_master.html#edit-puppetdbconf

Summary
-----


PuppetDB has three main groups of settings:

* The init script's configuration file, which sets the JVM heap size and the location of PuppetDB's main config file
* Logging settings, which go in the [logback.xml](#logging-config) file and can be changed without restarting PuppetDB
* All other settings, which go in PuppetDB's configuration file(s) and take effect after the service is restarted


Init Script Config File
-----

If you installed PuppetDB from packages or used the `rake install` installation method, an init script was created for PuppetDB. This script has its own configuration file, whose location varies by platform and by package:

OS and Package              | File
----------------------------|----------------------------
Redhat-like (open source)   | `/etc/sysconfig/puppetdb`
Redhat-like (PE)            | `/etc/sysconfig/pe-puppetdb`
Debian/Ubuntu (open source) | `/etc/default/puppetdb`
Debian/Ubuntu (PE)          | `/etc/default/pe-puppetdb`

 In this file, you can change the following settings:

- **`JAVA_BIN`** --- The location of the Java binary.
- **`JAVA_ARGS`** --- Command line options for the Java binary, most notably the `-Xmx` (max heap size) flag.
- **`USER`** --- The user PuppetDB should be running as.
- **`INSTALL_DIR`** --- The directory into which PuppetDB is installed.
- **`CONFIG`** --- The location of the PuppetDB config file, which may be a single file or a directory of .ini files.

### Configuring the Java Heap Size

To change the JVM heap size for PuppetDB, edit the [init script config file](#init-script-config-file) by setting a new value for the `-Xmx` flag in the `JAVA_ARGS` variable.

For example, to cap PuppetDB at 192MB of memory:

    JAVA_ARGS="-Xmx192m"

To use 1GB of memory:

    JAVA_ARGS="-Xmx1g"

### Configuring JMX Access

While all JMX metrics are exposed using the `/metrics` namespace, you can also
expose direct JMX access using standard JVM means as documented
[here](http://docs.oracle.com/javase/6/docs/technotes/guides/management/agent.html).
This can be done using the `JAVA_ARGS` init script setting, similar to configuring the heap size.

For example, adding the following JVM options will open
up a JMX socket on port 1099:

    JAVA_ARGS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=1099"


The logback Logging Config File
-----

Logging is configured with a logback.xml file, whose location is defined with the [`logging-config`](#logging-config) setting. If you change the log settings while PuppetDB is running, it will apply the new settings without requiring a restart.

[See the logback documentation][logback] for more information about logging options.


The PuppetDB Configuration File(s)
-----

PuppetDB is configured using an INI-style config format with several `[sections]`. This is very similar to the format used by Puppet. All of the sections and settings described below belong in the PuppetDB config file(s).

**Whenever you change PuppetDB's configuration settings, you must restart the service for the changes to take effect.**

You can change the location of the main config file in [the init script config file](#init-script-config-file). This location can point to a single configuration file or a directory of .ini files. If you specify a directory (_conf.d_ style), PuppetDB will merge the .ini files in alphabetical order.

If you've installed PuppetDB from a package, by default it will use the _conf.d_ config style. The default config directory is `/etc/puppetdb/conf.d` (or `/etc/puppetlabs/puppetdb/conf.d` for Puppet Enterprise).  If you're running from source, you may use the `-c` command-line argument to specify your config file or directory.

An example configuration file:

    [global]
    vardir = /var/lib/puppetdb
    logging-config = /var/lib/puppetdb/logback.xml

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //localhost:5432/puppetdb

    [jetty]
    port = 8080

### Playing Nice With the PuppetDB Module

If you [installed PuppetDB with the puppetlabs-puppetdb module][module], PuppetDB's settings will be managed by Puppet. Most of the settings you care about can be configured with the module's class parameters; see [the module's documentation](https://forge.puppetlabs.com/puppetlabs/puppetdb) for details.

If you _do_ need to change rare settings that the module doesn't manage, you can do the following:

Create a new class in a new module (something like `site::puppetdb::server::extra`), declare any number of `ini_setting` resources as shown below, set the class to refresh the `puppetdb::server` class, and assign it to your PuppetDB server.

{% highlight ruby %}
    # Site-specific PuppetDB settings. Declare this class on any node that gets the puppetdb::server class.
    class site::puppetdb::server::extra {

      # Restart the PuppetDB service if settings change
      Class[site::puppetdb::server::extra] ~> Class[puppetdb::server]

      # Get PuppetDB confdir
      include puppetdb::params
      $confdir = $puppetdb::params::confdir

      # Set resource defaults
      Ini_setting {
        ensure  => present,
        require => Class['puppetdb::server::validate_db'],
      }

      ini_setting {'puppetdb-catalog-hash-conflict-debugging':
        path    => "${confdir}/global.ini",
        section => 'global',
        setting => 'catalog-hash-conflict-debugging',
        value   => 'true',
      }
    }
{% endhighlight %}

`[global]` Settings
-----


The `[global]` section is used to configure application-wide behavior.

### `vardir`

This defines the parent directory for the MQ's data directory. Also, if a database isn't specified, the default database's files will be stored in `<vardir>/db`. The directory must exist and be writable by the PuppetDB user in order for the application to run.

### `logging-config`

This describes the full path to a [logback.xml](http://logback.qos.ch/manual/configuration.html) file. Covering all the options available for configuring logback is outside the scope of this document; see the aforementioned link for exhaustive information.

If this setting isn't provided, PuppetDB defaults to logging at INFO level to standard out.

If you installed from packages, PuppetDB will use the logback.xml file in the `/etc/puppetdb/` or `/etc/puppetlabs/puppetdb` directory. Otherwise, you can find an example file in the `ext` directory of the source.

You can edit the logging configuration file while PuppetDB is running, and it will automatically react to changes after a few seconds.

### `event-query-limit`

> **Deprecated:** With event streaming this setting is now ignored and will be removed in the future.

The maximum number of legal results that a resource event query can return.  If you issue a query that would result in more results than this value, the query will simply return an error.  (This can be used to prevent accidental queries that would yield huge numbers of results from consuming undesirable amounts of resources on the server.)

The default value is 20000.

### `update-server`

The URL to query when checking for newer versions; defaults to `http://updates.puppetlabs.com/check-for-updates`.
Overriding this setting may be useful if your PuppetDB server is firewalled and can't make external HTTP requests, in which case you could
configure a proxy server to send requests to the `updates.puppetlabs.com` URL
and override this setting to point to your proxy server.

### `catalog-hash-conflict-debugging`

When this is set to true, debugging information will be written to `<vardir>/debug/catalog-hashes` every time a catalog is received with a hash that is different than the previously received catalog for that host. Note that this should only be enabled when troubleshooting performance related issues with PuppetDB and the database server. This will output many files and could potentially slow down a production PuppetDB instance. See the [Troubleshooting Low Catalog Duplication guide][low_catalog_dupe] for more information on the outputted files and debugging this problem.

### `url-prefix`

This optional setting may be used to mount the PuppetDB web application at a URL other than "/".  This should not be necessary
unless you intend to run additional web applications in the same server with your PuppetDB instance.  **NOTE:** if you change
this setting, you must also set the corresponding setting in your Puppet Master's [puppetdb.conf][puppetdb.conf] file.


`[puppetdb]` Settings
-----

The `[puppetdb]` section is used to configure PuppetDB application-specific behavior.

### `certificate-whitelist`

Optional. This describes the path to a file that contains a list of certificate names, one per line.  Incoming HTTPS requests will have their certificates validated against this list of names and only those with an _exact_ matching entry will be allowed through. (For a puppet master, this compares against the value of the `certname` setting, rather than the `dns_alt_names` setting.)

If not supplied, PuppetDB uses standard HTTPS without any additional authorization. All HTTPS clients must still supply valid, verifiable SSL client certificates.


`[database]` Settings
-----

The `[database]` section configures PuppetDB's database settings.

PuppetDB can use either **a built-in HSQLDB database** or **a PostgreSQL database.** If no database information is supplied, an HSQLDB database at `<vardir>/db` will be used.

> **FAQ: Why no MySQL or Oracle support?**
>
> MySQL lacks several features that PuppetDB relies on; the most notable is recursive queries. We have no plans to ever support MySQL.
>
> Depending on demand, Oracle support may be forthcoming in a future version of PuppetDB. This hasn't been decided yet.

### Using Built-in HSQLDB

To use an HSQLDB database at the default `<vardir>/db`, you can simply remove all database settings. To configure the DB for a different location, put the following in the `[database]` section:

    classname = org.hsqldb.jdbcDriver
    subprotocol = hsqldb
    subname = file:</PATH/TO/DB>;hsqldb.tx=mvcc;sql.syntax_pgs=true

Replace `</PATH/TO/DB>` with the filesystem location in which you'd like to persist the database.

Do not use the `username` or `password` settings.

### Using PostgreSQL

Before using the PostgreSQL backend, you must set up a PostgreSQL server, ensure that it will accept incoming connections, create a user for PuppetDB to use when connecting, and create a database for PuppetDB. Completely configuring PostgreSQL is beyond the scope of this manual, but if you are logged in as root on a running Postgres server, you can create a user and database as follows:

    $ sudo -u postgres sh
    $ createuser -DRSP puppetdb
    $ createdb -E UTF8 -O puppetdb puppetdb
    $ exit

If you are running PostgreSQL 9.3 or above you should install the
regexp optimized index extension [`pg_trgm`][pg_trgm]. This may
require installing the `postgresql-contrib` (or equivalent) package,
depending on your distribution:

    $ sudo -u postgres sh
    $ psql puppetdb -c 'create extension pg_trgm'
    $ exit

Next you will most likely need to modify the `pg_hba.conf` file to allow for md5 authentication from at least localhost. To locate the file you can either issue a `locate pg_hba.conf` command (if your distribution supports it) or consult your distribution's documentation for the PostgreSQL `confdir`.

The following example `pg_hba.conf` file allows md5 authentication from localhost for both IPv4 and IPv6 connections:

    # TYPE  DATABASE   USER   CIDR-ADDRESS  METHOD
    local   all        all                  md5
    host    all        all    127.0.0.1/32  md5
    host    all        all    ::1/128       md5

Restart PostgreSQL and ensure you can log in by running:

    $ sudo service postgresql restart
    $ psql -h localhost puppetdb puppetdb

To configure PuppetDB to use this database, put the following in the `[database]` section:

    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //<HOST>:<PORT>/<DATABASE>
    username = <USERNAME>
    password = <PASSWORD>

Replace `<HOST>` with the DB server's hostname. Replace `<PORT>` with the port on which PostgreSQL is listening. Replace `<DATABASE>` with the name of the database you've created for use with PuppetDB.

#### Using SSL With PostgreSQL

It's possible to use SSL to protect connections to the database. There are several extra steps and considerations when doing so; see the
[PostgreSQL SSL setup page][postgres_ssl] for complete details.

The main difference in the config file is that you must be sure to add `?ssl=true` to the `subname` setting:

    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true


### `gc-interval`

This controls how often, in minutes, to compact the database. The compaction process reclaims space and deletes unnecessary rows. If not supplied, the default is every 60 minutes.


### `node-ttl`

Auto-deactivate nodes that haven't seen any activity (no new catalogs,
facts, etc) in the specified amount of time. You may specify the time
as a string using any of the following suffixes:

    `d`  - days
    `h`  - hours
    `m`  - minutes
    `s`  - seconds
    `ms` - milliseconds

So, e.g., a value of `30d` would set the time-to-live to 30 days, and a value of
`48h` would set the time-to-live to 48 hours.

Nodes will be checked for staleness every `gc-interval` minutes. Manual
deactivation will continue to work as always.

If unset, auto-deactivation of nodes is disabled.

### `node-purge-ttl`

Automatically delete nodes that have been _deactivated_ for the specified
amount of time. This will also delete all facts, catalogs and reports for the
node. This ttl may be specified the same way as `node-ttl` above.

If unset, auto-deletion of nodes is disabled.

### `report-ttl`

Automatically delete reports that are older than the specified amount of time.
You may specify the time as a string using any of the suffixes described in the
`node-ttl` section above.

Outdated reports will be deleted during the database garbage collection, which
runs every `gc-interval` minutes.

If unset, the default value is 14 days.

### `classname`

This sets the JDBC class to use. Set this to:

* `org.hsqldb.jdbcDriver` when using the embedded database
* `org.postgresql.Driver` when using PostgreSQL

### `subprotocol`

Set this to:

* `hsqldb` when using the embedded database
* `postgresql` when using PostgreSQL

### `subname`

This describes where to find the database. Set this to:

* `file:</PATH/TO/DB>;hsqldb.tx=mvcc;sql.syntax_pgs=true` when using the embedded database, replacing `</PATH/TO/DB>` with a local filesystem path
* `//<HOST>:<PORT>/<DATABASE>` when using PostgreSQL, replacing `<HOST>` with the DB server's hostname, `<PORT>` with the port on which PostgreSQL is listening, and `<DATABASE>` with the name of the database
    * Append `?ssl=true` to this if your PostgreSQL server is using SSL.

### `username`

This is the username to use when connecting. Only used with PostgreSQL.

### `password`

This is the password to use when connecting. Only used with PostgreSQL.

### `log-slow-statements`

This sets the number of seconds before an SQL query is considered "slow." Slow SQL queries are logged as warnings, to assist in debugging and tuning. Note PuppetDB does not interrupt slow queries; it simply reports them after they complete.

The default value is 10 seconds. A value of 0 will disable logging of slow queries.

### `conn-max-age`

The maximum time (in minutes), for a pooled connection to remain unused before it is closed off.

If not supplied, we default to 60 minutes.

### `conn-keep-alive`

This sets the time (in minutes), for a connection to remain idle before sending a test query to the DB. This is useful to prevent a DB from timing out connections on its end.

If not supplied, we default to 45 minutes.

### `conn-lifetime`

The maximum time (in minutes) a pooled connection should remain open. Any connections older than this setting will be closed off. Connections currently in use will not be affected until they are returned to the pool.

If not supplied, we won't terminate connections based on their age alone.

###`statements-cache-size`

This setting defines how many prepared statements are cached automatically. For a large amount of dynamic queries this number could be increased to increase performance, at the cost of memory consumption and database resources.

If not supplied, we default to 1000.

`[read-database]` Settings
-----

The `[read-database]` section configures PuppetDB's _read-database_ settings, useful when running a PostgreSQL [Hot Standby](http://wiki.postgresql.org/wiki/Hot_Standby) cluster.  Currently only configuring a Postgres read-database is supported.  See the Postgres docs [here](http://wiki.postgresql.org/wiki/Hot_Standby) for details on configuring the cluster.  The `[read-database]` portion of the configuration is in addition to the `[database]` settings.  If `[read-database]` is specified, `[database]` must also be specified.

To configure PuppetDB to use a read-only database from the cluster, put the following in the `[read-database]` section:

    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //<HOST>:<PORT>/<DATABASE>
    username = <USERNAME>
    password = <PASSWORD>

Replace `<HOST>` with the DB server's hostname. Replace `<PORT>` with the port on which PostgreSQL is listening. Replace `<DATABASE>` with the name of the database you've created for use with PuppetDB.

#### Using SSL With PostgreSQL

It's possible to use SSL to protect connections to the database. There are several extra steps and considerations when doing so; see the
[PostgreSQL SSL setup page][postgres_ssl] for complete details.

The main difference in the config file is that you must be sure to add `?ssl=true` to the `subname` setting:

    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true

### `classname`

This sets the JDBC class to use. This should be `org.postgresql.Driver`.

### `subprotocol`

Set this to `postgresql`.

### `subname`

This describes where to find the database. Set this to:

* `//<HOST>:<PORT>/<DATABASE>` when using PostgreSQL, replacing `<HOST>` with the DB server's hostname, `<PORT>` with the port on which PostgreSQL is listening, and `<DATABASE>` with the name of the database
    * Append `?ssl=true` to this if your PostgreSQL server is using SSL.

### `username`

This is the username to use when connecting.

### `password`

This is the password to use when connecting.

### `log-slow-statements`

This sets the number of seconds before an SQL query is considered "slow." Slow SQL queries are logged as warnings, to assist in debugging and tuning. Note PuppetDB does not interrupt slow queries; it simply reports them after they complete.

The default value is 10 seconds. A value of 0 will disable logging of slow queries.

### `conn-max-age`

The maximum time (in minutes), for a pooled connection to remain unused before it is closed off.

If not supplied, we default to 60 minutes.

### `conn-keep-alive`

This sets the time (in minutes), for a connection to remain idle before sending a test query to the DB. This is useful to prevent a DB from timing out connections on its end.

If not supplied, we default to 45 minutes.

### `conn-lifetime`

The maximum time (in minutes) a pooled connection should remain open. Any connections older than this setting will be closed off. Connections currently in use will not be affected until they are returned to the pool.

If not supplied, we won't terminate connections based on their age alone.


`[command-processing]` Settings
-----

The `[command-processing]` section configures the command-processing subsystem.

Every change to PuppetDB's data stores arrives via **commands** that are inserted into a message queue (MQ). Command processor threads pull items off of that queue, persisting those changes.

### `threads`

This defines how many command processing threads to use. Each thread can process a single command at a time. [The number of threads can be tuned based on what you see in the performance dashboard.][dashboard]

This setting defaults to half the number of cores in your system.

### `dlo-compression-threshold`

This setting specifies the maximum duration to keep messages in the dead-letter office before archiving them. This process will check for compressible messages on startup and after every `gc-interval`, but will only perform the archive once per `dlo-compression-threshold`. The same format can be used as for the `node-ttl` setting above. If set to 0 seconds, this behavior will be disabled. The default value is 1 day.

### `store-usage`

This setting sets the maximum amount of space in megabytes that PuppetDB's ActiveMQ can use for persistent message storage.

### `temp-usage`

This setting sets the maximum amount of space in megabytes that PuppetDB's ActiveMQ can use for temporary message storage.

### `max-frame-size`

This setting sets the maximum frame size for persisted activemq messages
supplied in bytes.  Default value is 209715200 (i.e 200 MB).

`[jetty]` (HTTP) Settings
-----

The `[jetty]` section configures HTTP for PuppetDB.

> **Note:** If you are using Puppet Enterprise and want to enable the PuppetDB dashboard from the PE console, refer to [Changing PuppetDB's Parameters](/pe/latest/maintain_console-db.html#changing-puppetdbs-parameters) for more information. PE users should not edit `jetty.ini`.

### `host`

This sets the IP interface to listen on for _unencrypted_ HTTP traffic. If not supplied, we bind to `localhost`, which will reject connections from anywhere but the PuppetDB server itself. To listen on all available interfaces, use `0.0.0.0`.

To avoid DNS resolution confusion, it is recommended that if you wish to set this to something other than `localhost` an IP address should be used instead of a hostname.

> **Note:** Unencrypted HTTP is the only way to view the [performance dashboard][dashboard], since PuppetDB uses host verification for SSL. However, it can also be used to make any call to PuppetDB's API, including inserting exported resources and retrieving arbitrary data about your Puppet-managed nodes. **If you enable cleartext HTTP, you MUST configure your firewall to protect unverified access to PuppetDB.**

### `port`

This sets what port to use for _unencrypted_ HTTP traffic. If not supplied, we won't listen for unencrypted traffic at all.

### `max-threads`

This sets the maximum number of threads assigned to responding to HTTP and HTTPS requests, effectively changing how many concurrent requests can be made at one time. Defaults to 50.

> **Note:** Due to how our web-server (Jetty 7) behaves, this setting must be higher then the number of CPU's on your system or it will stop processing any HTTP requests.

### `ssl-host`

This sets IP interface to listen on for _encrypted_ HTTPS traffic. If not supplied, we bind to `localhost`. To listen on all available interfaces, use `0.0.0.0`.

To avoid DNS resolution confusion, it is recommended that if you wish to set this to something other than `localhost` an IP address should be used instead of a hostname.

### `ssl-port`

This sets the port to use for _encrypted_ HTTPS traffic. If not supplied, we won't listen for encrypted traffic at all.

### `ssl-cert`

This sets the path to the server certificate PEM file used by the PuppetDB web service for HTTPS.

> **Note:** This setting overrides the alternate configuration settings `keystore` and `key-password`.

### `ssl-key`

This sets the path to the private key PEM file that corresponds with the `ssl-cert`, it used by the PuppetDB web service for HTTPS.

> **Note:** This setting overrides the alternate configuration settings `keystore` and `key-password`.

### `ssl-ca-cert`

This sets the path to the CA certificate PEM file used for client authentication. Authorized clients must be signed by the CA that that corresponds to this certificate.

> **Note:** This setting overrides the alternate configuration settings `truststore` and `trust-password`.

### `keystore`

This sets the path to a Java keystore file containing the key and certificate to be used for HTTPS.

### `key-password`

This sets the passphrase to use for unlocking the keystore file.

### `truststore`

This describes the path to a Java keystore file containing the CA certificate(s) for your puppet infrastructure.

### `trust-password`

This sets the passphrase to use for unlocking the truststore file.


### `cipher-suites`

Optional. A comma-separated list of cryptographic ciphers to allow for incoming SSL connections. Valid names are listed in the [official JDK cryptographic providers documentation](http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html#SupportedCipherSuites); you'll need to use the all-caps cipher suite name.

If not supplied, PuppetDB uses the default cipher suites for your local system on JDK versions older than 1.7.0u6. On newer JDK versions, PuppetDB will use only non-DHE cipher suites.

### `ssl-protocols`

Optional. A comma-separated list of protocols to allow for incoming SSL connections. Valid names are listed in the [official JDK cryptographic protocol documentation](http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html#SunJSSEProvider); you'll need to use the names with verbatim capitalization. For example: `TLSv1, TLSv1.1, TLSv1.2`.

If not supplied, PuppetDB uses a default of `TLSv1, TLSv1.1, TLSv1.2`. By default SSLv3 is not included in that list due to known vulnerabilities. Users wanting to use SSLv3 need to specify it in their list explicitly.

`[repl]` Settings
-----

The `[repl]` section configures remote runtime modification.  For more detailed info, see [Debugging with the Remote REPL][repl].

Enabling a remote [REPL](http://en.wikipedia.org/wiki/Read%E2%80%93eval%E2%80%93print_loop) allows you to manipulate the behavior of PuppetDB at runtime. This should only be done for debugging purposes, and is thus disabled by default. An example configuration stanza:

    [repl]
    enabled = true
    type = nrepl
    port = 8081

### `enabled`

Set to `true` to enable the REPL. Defaults to false.

### `type`

Either `nrepl` or `telnet`.

The _telnet_ repl type opens up a socket you can connect to via telnet. The interface is pretty low-level and raw (no completion or command history), but it is nonetheless usable on just about any system without the need of any external tools other than telnet itself.

The _nrepl_ repl type opens up a socket you can connect to via any nrepl-protocol client, such as via [Leiningen](https://github.com/technomancy/leiningen) using `lein repl :connect localhost:8082` or via Emacs (via `M-x nrepl`), Vim, or integration with other editors like Netbeans or Eclipse. This is much more user-friendly than telnet.

### `port`

The port to use for the REPL.

### `host`

Specifies the host or IP address for the repl service to listen on. By default this is `127.0.0.1` only, as this is an insecure channel this is the only recommended setting for production environments. Although this is generally not recommended for production, you can listen on all interfaces, you can specify `0.0.0.0` for example.
