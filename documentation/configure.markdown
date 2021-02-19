---
title: "Configuring PuppetDB"
layout: default
canonical: "/puppetdb/latest/configure.html"
---

[java-patterns]: https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html
[logback]: http://logback.qos.ch/manual/configuration.html
[dashboard]: ./maintain_and_tune.html#monitor-the-performance-dashboard
[pe-dashboard]: https://support.puppet.com/hc/en-us/articles/208484488-Enable-and-view-the-PuppetDB-performance-dashboard-for-Puppet-Enterprise-3-3-2-to-2019-1
[repl]: ./repl.html
[pg_trgm]: http://www.postgresql.org/docs/current/static/pgtrgm.html
[postgres_ssl]: ./postgres_ssl.html
[migration_coordination]: ./migration_coordination.html
[module]: ./install_via_module.html
[puppetdb.conf]: ./connect_puppet_server.html#edit-puppetdbconf
[ha]: ./ha.html
[node-ttl]: #node-ttl
[admin-cmd]: ./api/admin/v1/cmd.html

PuppetDB has three main groups of settings:

* The init script's configuration file, which sets the JVM heap size and the location of PuppetDB's main config file.
* Logging settings, which go in the [logback.xml](#logging-config) file and can be changed without restarting PuppetDB.
* All other settings, which go in PuppetDB's configuration file(s) and take effect after the service is restarted.

Init Script Config File
-----

If you installed PuppetDB from packages or used the `rake install`
installation method, an init script was created for PuppetDB. This
script has its own configuration file, the location of which varies by
platform and by package:

OS and Package               | File
-----------------------------|----------------------------
Red Hat-like (open source)   | `/etc/sysconfig/puppetdb`
Red Hat-like (PE)            | `/etc/sysconfig/pe-puppetdb`
Debian/Ubuntu (open source)  | `/etc/default/puppetdb`
Debian/Ubuntu (PE)           | `/etc/default/pe-puppetdb`

 In this file, you can change the following settings:

- **`JAVA_BIN`**: the location of the Java binary.
- **`JAVA_ARGS`**: command line options for the Java binary, most notably the `-Xmx` (max heap size) flag.
- **`USER`**: the user PuppetDB should be running as.
- **`INSTALL_DIR`**: the directory into which PuppetDB is installed.
- **`CONFIG`**: the location of the PuppetDB config file, which may be a single file or a directory of .ini files.

### Configuring the Java heap size

To change the JVM heap size for PuppetDB, edit the [init script config
file](#init-script-config-file) by setting a new value for the `-Xmx`
flag in the `JAVA_ARGS` variable.

For example, to cap PuppetDB at 192MB of memory:

    JAVA_ARGS="-Xmx192m"

To use 1GB of memory:

    JAVA_ARGS="-Xmx1g"

### Configuring JMX access

While all JMX metrics are exposed using the `/metrics` namespace, you can also
expose direct JMX access using standard JVM means as documented
[here](http://docs.oracle.com/javase/6/docs/technotes/guides/management/agent.html).
This can be done using the `JAVA_ARGS` init script setting, similar to configuring the heap size.

For example, adding the following JVM options will open
up a JMX socket on port 1099:

    JAVA_ARGS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=1099"


The Logback logging-config file
-----

Logging is configured with a logback.xml file, whose location is
defined with the [`logging-config`](#logging-config) setting. If you
change the log settings while PuppetDB is running, it will apply the
new settings without requiring a restart.

[See the Logback documentation][logback] for more information about logging options.


The PuppetDB configuration file(s)
-----

PuppetDB is configured using an INI-style config format with several
`[sections]`. This is very similar to the format used by Puppet. All
of the sections and settings described below belong in the PuppetDB
config file(s).

> **Note:** Whenever you change PuppetDB's configuration settings, you must restart the service for the changes to take effect.

You can change the location of the main config file in [the init
script config file](#init-script-config-file). This location can point
to a single configuration file or a directory of .ini files. If you
specify a directory (in _conf.d_ style), PuppetDB will merge the .ini
files in alphabetical order.

If you've installed PuppetDB from a package, by default it will use
the _conf.d_ config style. The default config directory is
`/etc/puppetlabs/puppetdb/conf.d`. If you're running from source, you
may use the `-c` command-line argument to specify your config file or
directory.

An example configuration file:

    [global]
    vardir = /var/lib/puppetdb
    logging-config = /var/lib/puppetdb/logback.xml

    [database]
    subname = //localhost:5432/puppetdb

    [puppetdb]
    certificate-allowlist = /path/to/file/containing/certnames
    disable-update-checking = false

    [jetty]
    port = 8080

### Playing nice with the PuppetDB module

If you [installed PuppetDB with the puppetlabs-puppetdb
module][module], PuppetDB's settings will be managed by Puppet. Most
of the settings you care about can be configured with the module's
class parameters; see [the module's
documentation](https://forge.puppetlabs.com/puppetlabs/puppetdb) for
details.

If you _do_ need to change those rare settings that the module doesn't
manage, you can do the following:

Create a new class in a new module (something like
`site::puppetdb::server::extra`), declare any number of `ini_setting`
resources as shown below, set the class to refresh the
`puppetdb::server` class, and assign it to your PuppetDB server.

~~~ ruby
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

      ini_setting {'puppetdb-extra-setting':
        path    => "${confdir}/global.ini",
        section => 'global',
        setting => <some-extra-setting>,
        value   => 'true',
      }
    }
~~~

`[global]` settings
-----

The `[global]` section is used to configure application-wide behavior.

### `vardir`

This defines the parent directory for the MQ's data directory. The
directory must exist and be writable by the PuppetDB user in order for
the application to run.

### `logging-config`

This describes the full path to a
[logback.xml](http://logback.qos.ch/manual/configuration.html)
file. Covering all the options available for configuring Logback is
outside the scope of this guide: see the [Logback documentation][logback] for
exhaustive information.

If this setting isn't provided, PuppetDB defaults to logging at INFO
level to standard out.

If you installed from packages, PuppetDB will use the logback.xml file
in the `/etc/puppetdb/` or `/etc/puppetlabs/puppetdb`
directory. Otherwise, you can find an example file in the `ext`
directory of the source.

You can edit the logging configuration file while PuppetDB is running,
and it will automatically react to changes after a few seconds.

### `update-server`

The URL to query when checking for newer versions; defaults to
`https://updates.puppetlabs.com/check-for-updates`. Overriding this
setting may be useful if your PuppetDB server is firewalled and can't
make external HTTP requests. In this case you can configure a proxy
server to send requests to the `updates.puppetlabs.com` URL and
override this setting to point to your proxy server.

`[puppetdb]` settings
-----

The `[puppetdb]` section is used to configure PuppetDB
application-specific behavior.

### `certificate-allowlist`

Optional. This describes the path to a file that contains a list of
certificate names, one per line. Incoming HTTPS requests will have
their certificates validated against this list of names and only those
with an **exact** matching entry will be allowed through. (For a Puppet
Server, this compares against the value of the `certname` setting,
rather than the `dns_alt_names` setting.)

If not supplied, PuppetDB uses standard HTTPS without any additional
authorization. All HTTPS clients must still supply valid, verifiable
SSL client certificates.

> **Note**: This setting replaces the deprecated `certificate-whitelist` setting which will be retired in a future release

### `historical-catalogs-limit` (PE only)

> **Note**: This setting has no effect and will be retired in a future release.

### `disable-update-checking`

Optional. Setting this to `true` disables checking for updated
versions of PuppetDB and sending basic analytics data to Puppet. Defaults to `false`.

If `disable-update-checking` is set to `false`, PuppetDB checks for updates upon start or restart, and every 24 hours thereafter, and sends the following data to Puppet:

* Product name
* Database name
* Database version
* PuppetDB version
* IP address
* Data collection timestamp

The data Puppet collects provides just one of many methods we use for learning about our community of users. The more we know about how you use Puppet, the better we can address your needs. No personally identifiable information is collected, and the data we collect is never used or shared outside Puppet.

### `log-queries`
Optional. Setting this to `true` will enable debug level logging of the internal
AST and SQL that PuppetDB generates for all queries. This can be useful when
debugging query performance. If unset, the default value is `false`.
[See the Logback documentation][logback] for more information about logging
options and how to enable debug level logging.

`[database]` settings
-----

The `[database]` section configures PuppetDB's database settings.
PuppetDB stores its data in PostgreSQL.

> **FAQ: Why no MySQL or Oracle support?**
>
> MySQL lacks several features that PuppetDB relies on, most notably including recursive queries. We have no plans to ever support MySQL.
>
> Depending on demand, Oracle support may be forthcoming in a future version of PuppetDB. This hasn't been decided yet.

### Using PostgreSQL

Before using the PostgreSQL backend, you must set up a PostgreSQL
server. Note that users installing PuppetDB via [the module][module]
will already have PostgreSQL configured properly and these steps
should not be necessary.

If you are not using the module, you will need to configure a PostgreSQL server, version 11 or newer, to include a user and an empty database for PuppetDB, and the server must accept incoming connections to that database as that user.  PostgreSQL connections and authentication are discussed [here](https://www.postgresql.org/docs/11/static/auth-pg-hba-conf.html), and setting up users and databases is discussed in the [Getting
Started](https://www.postgresql.org/docs/11/static/tutorial-start.html)
section of the [PostgreSQL
manual](https://www.postgresql.org/docs/11/static/index.html).

Completely configuring PostgreSQL is beyond the scope of this guide,
but a example setup is described below. First, you can create a user
and database as follows:

    $ sudo -u postgres sh
    $ createuser -DRSP puppetdb
    $ createdb -E UTF8 -O puppetdb puppetdb
    $ exit

Particularly if you plan to run more than one PuppetDB instance
connecting to the same database, we recommend you also
[add and use a "migrator" user](#coordinating-database-migrations)

You should install the RegExp-optimized index extension
[`pg_trgm`][pg_trgm]. This may require installing the
`postgresql-contrib` (or equivalent) package, depending on your
distribution:

    $ sudo -u postgres sh
    $ psql puppetdb -c 'create extension pg_trgm'
    $ exit

Next, you will most likely need to modify the `pg_hba.conf` file to
allow for MD5 authentication from at least localhost. To locate the
file you can either issue a `locate pg_hba.conf` command (if your
distribution supports it) or consult your distribution's documentation
for the PostgreSQL `confdir`.

The following example `pg_hba.conf` file allows MD5 authentication
from localhost for both IPv4 and IPv6 connections:

    # TYPE  DATABASE   USER   CIDR-ADDRESS  METHOD
    local   all        all                  md5
    host    all        all    127.0.0.1/32  md5
    host    all        all    ::1/128       md5

Restart PostgreSQL and ensure you can log in by running:

    $ sudo service postgresql restart
    $ psql -h localhost puppetdb puppetdb

To configure PuppetDB to use this database, put the following in the `[database]` section:

    subname = //<HOST>:<PORT>/<DATABASE>
    username = <USERNAME>
    password = <PASSWORD>

Replace `<HOST>` with the DB server's hostname. Replace `<PORT>` with
the port on which PostgreSQL is listening. Replace `<DATABASE>` with
the name of the database you've created for use with PuppetDB.

#### Using SSL With PostgreSQL

It's possible to use SSL to protect connections to the database. There
are several extra steps and considerations when doing so. See the
[PostgreSQL SSL setup page][postgres_ssl] for complete details.

The main difference in the config file is that you must be sure to add
`?ssl=true` to the `subname` setting:

    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true

#### Coordinating database migrations

If you plan to run more than one PuppetDB instance connected to the
same database, you must ensure that two instances do not attempt to
upgrade the database simultaneously.  Further, you should also ensure
that a PuppetDB server never tries to use a database whose migration
level (data format version) differs from the one it expects.

PuppetDB will refuse to start if it detects an unexpected migration
level, which covers many cases, but won't help, for example, if a new
version of PuppetDB is started while older versions are still running.

One direct solution, using upgrades as an example, is to just make
sure to stop all of your PuppetDB instances, then run one instance of
the newer version to perform any necessary upgrade via

    puppetdb upgrade -c .../normal-config.ini

Once that's finished, relaunch all of your instances using the
newer version of PuppetDB.

PuppetDB can also be configured to attempt to automatically guard you
against these risks.  To do so, first make sure all but one of your PuppetDB
instances are configured with `[database]` [migrate option](#migrate)
set to `false` in the config file.

This will prevent PuppetDB from attempting to upgrade the database at
startup (it will just quit on a mismatch instead).  You will of course
need to set it to `true` (the default) in the config file of the
instance you want to perform your migrations (either at startup or via
the `upgrade` subcommand shown above).

Setting `migrate` to false helps prevent unexpected migrations, but it
doesn't prevent a migration from starting while other (soon to be
invalid/out-of-date) PuppetDB instances continue to access the
database.  That's true even though newer PuppetDB versions have a
check to prevent them from creating new connections to an unrecognized
database version because PuppetDB can continue to use any connections
that are already open (and either active or waiting in the connection
pool).

To help prevent all acccess to an unexpected database version, you can
provide PuppetDB with a separate, suitably configured PostgreSQL user
(role), for migrations.  That role must have the ability to grant and
revoke connection privileges to/from the normal PuppetDB database
user, and it must also be allowed to terminate the normal user's
existing connections.  One way to arrange that is to do sometthing
like this after creating the `puppetdb` user as described above:

    $ sudo -u postgres sh
    $ createuser -DRSP puppetdb_migrator
    $ psql puppetdb -c 'revoke connect on database puppetdb from public'
    $ psql puppetdb -c 'grant connect on database puppetdb to puppetdb_migrator with grant option'
    $ psql puppetdb -c 'grant connect on database puppetdb to puppetdb' \
        -U puppetdb_migrator
    $ psql puppetdb -c 'grant puppetdb to puppetdb_migrator'
    $ exit

Then specify `puppetdb_migrator` as the
[migrator-username](#migrator-username) and set the
[migrator-password](#migrator-password) as described below.

See the [migration coordination documentation](migration_coordination)
for a more detailed explanation of the process.

### `gc-interval`

This controls how often, in minutes, to compact the database. The
compaction process reclaims space and deletes unnecessary rows. If not
supplied, the default is every 60 minutes.  If set to zero, all
database GC processes will be disabled.

### `node-ttl`

Mark as 'expired' nodes that haven't seen any activity (no new catalogs,
facts, or reports) in the specified amount of time. Expired nodes behave the same
as manually-deactivated nodes.

You may specify the time as a string using any of the following suffixes:

    `d`  - days
    `h`  - hours
    `m`  - minutes
    `s`  - seconds
    `ms` - milliseconds

For example, a value of `30d` would set the time-to-live to 30 days, and a value of
`48h` would set the time-to-live to 48 hours.

Nodes will be checked for staleness every `gc-interval` minutes. Manual
deactivation will continue to work as always.

If unset, nodes are auto-expired after 7 days of inactivity. If set to 0s,
auto-expiration of nodes is disabled.

### `node-purge-ttl`

Automatically delete nodes that have been deactivated or expired for the
specified amount of time. This will also delete all facts, catalogs, and reports
for the relevant nodes. This TTL may be specified the same way as `node-ttl` above.

If unset, nodes are purged after 14 days. If set to 0s, auto-deletion of nodes
is disabled.

### `node-purge-gc-batch-limit`

Nodes will be purged in batches of this size, one batch per
`gc-interval`.  If unset, the batch limit will be 25, and if you
expect to generate eligible nodes faster than that (on average), you
should either increase this limit so that PuppetDB will be able to
keep up, or complement the automatic GC process with manual
`purge_node` requests to the [cmd endpoint][admin-cmd] to cover the
excess.

### `report-ttl`

Automatically delete reports that are older than the specified amount of time.
You may specify the time as a string using any of the suffixes described in the
`node-ttl` section above.

Outdated reports will be deleted during the database garbage collection, which
runs every `gc-interval` minutes.

If unset, the default value is 14 days.

### `resource-events-ttl`

Automatically delete report events older than the specified time. Reports are
unaffected until their respective TTLs.

This allows for more fine-grained control of the expiration of reports. If
unset, it will default to the value of `report-ttl`. It may not be set to a
value larger than `report-ttl`.

Note: this value is in UTC days - any value will be rounded to the nearest day.
Example: 14 hours will be 1 day, 25 hours will be 2 days.

See `node-ttl` above for the format of this value.

### `subname`

This describes where to find the database. It should be something like
`//<HOST>:<PORT>/<DATABASE>`, replacing `<HOST>` with the DB server's
hostname, `<PORT>` with the port on which PostgreSQL is listening, and
`<DATABASE>` with the name of the database. Append `?ssl=true` to
this if your PostgreSQL server is using SSL.

### `username`

The database user specified for connections performing normal
operations (queries, command processing, etc.).

### `connection-username`
The database user for special cases when the database connection username
is different from the username reported by the database.
An example is managed PostgreSQL in Azure, in order to connect we need to specify
`username@hostname`, but the user reported by the database is `username`.
If not specified, it will default to `username`


### `password`

This is the password to use when connecting. Only used with PostgreSQL.

### `migrator-username`

The database user specified for database migration operations, in
particular the database validation and migration at startup.  Defaults
to the `username`.

### `connection-migrator-username`
The database migrator user for special cases when the database connection username
is different from the username reported by the database.
An example is managed PostgreSQL in Azure, in order to connect we need to specify
`username@hostname`, but the user reported by the database is `username`.
If not specified, it will default to `migrator-username`

### `migrator-password`

This is the password to use when connecting for database migrations.
Defaults to `password`.

### `migrate`

When set to `true` (the default), PuppetDB will upgrade the data in
the database to the latest format at startup.  When `false`, PuppetDB
will exit with an error status if the format version is not the one it
expects, whether newer or older.

### `maximum-pool-size`

From the [HikariCP documentation](https://github.com/brettwooldridge/HikariCP):

> "This property controls the maximum size that the pool is allowed to reach,
> including both idle and in-use connections. Basically this value will
> determine the maximum number of actual connections to the database backend. A
> reasonable value for this is best determined by your execution environment."

When the pool reaches this size, and no idle connections are available, attempts
to get a connection will wait for `connection-timeout` milliseconds before timing
out.

The default value is 25. Note that PuppetDB will use one pool for writes and another
for reads, so the total number of connections used will be twice this setting.

### `conn-max-age`

The maximum time (in minutes), for a pooled connection to remain
unused before it is closed off.

If not supplied, the default value is 60 minutes.

### `conn-lifetime`

The maximum time (in minutes) a pooled connection should remain
open. Any connections older than this setting will be closed
off. Connections currently in use will not be affected until they are
returned to the pool.

If not supplied, we won't terminate connections based on their age
alone.

### `connection-timeout`

The maximum time to wait (in milliseconds) to acquire a connection
from the pool of database connections. If not supplied, defaults to
3000.

### `facts-blocklist`

Optional.  A list of fact names to be ignored whenever submitted.  The
`facts-blocklist-type` determines whether the names are matched
literally or as [Java regular expresions][java-patterns].

The names must be comma-separated in an INI configuration file, or a
list in a HOCON file:

 * INI: `facts-blocklist = fact1, fact2, fact3`
 * HOCON: `facts-blocklist = ["fact1", "fact2", "fact3"]`

When matching lterally, the entire fact name (not including the path)
must completely match one of the `facts-blocklist` entries in order to
be blocklisted.  When matching regular expressions, the name must
match the entire pattern.  For example the pattern "xyz" will not
match the fact "123xyzabc", but ".\*xyz.\*" will.

> **Note**: This setting replaces the deprecated `facts-blacklist` setting which will be retired in a future release

### `facts-blocklist-type`

Optional.  When set to `literal` (or not set) the `facts-blocklist`
names will be matched literally.  When set to `regex` (the only other
legal value), the names will be matched as [Java regular
expresions][java-patterns].  See the `facts-blocklist` description
above for additional information.

> **Note**: This setting replaces the deprecated `facts-blacklist-type` setting which will be retired in a future release


### `schema-check-interval`

This controls how often, in milliseconds, to check the schema version PuppetDB
is compatible with against the database's schema version. The default is every
30 seconds. If a mismatch is detected PuppetDB will exit with an error message
suggesting appropriate action. If set to zero, this check is disabled.

`[read-database]` settings
-----

The `[read-database]` section configures PuppetDB's _read-database_
settings, useful when running a PostgreSQL [Hot
Standby](http://wiki.postgresql.org/wiki/Hot_Standby) cluster.
Currently, only configuring a PostgreSQL read-database is supported.  See
the PostgreSQL documentation [here](http://wiki.postgresql.org/wiki/Hot_Standby)
for details on configuring the cluster. The `[read-database]` portion
of the configuration is in addition to the `[database]` settings. If
`[read-database]` is specified, `[database]` must also be specified.

To configure PuppetDB to use a read-only database from the cluster,
add the following to the `[read-database]` section:

    subname = //<HOST>:<PORT>/<DATABASE>
    username = <USERNAME>
    password = <PASSWORD>

Replace `<HOST>` with the DB server's hostname. Replace `<PORT>` with
the port on which PostgreSQL is listening. Replace `<DATABASE>` with
the name of the database you've created for use with PuppetDB.

#### Using SSL With PostgreSQL

It's possible to use SSL to protect connections to the database. There
are several extra steps and considerations when doing so; see the
[PostgreSQL SSL setup page][postgres_ssl] for complete details.

The main difference in the config file is that you must be sure to add
`?ssl=true` to the `subname` setting:

    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true

### `subname`

This describes where to find the database. Set this to `//<HOST>:<PORT>/<DATABASE>` when using PostgreSQL, replacing `<HOST>` with the DB server's hostname, `<PORT>` with the port on which PostgreSQL is listening, and `<DATABASE>` with the name of the database.

Append `?ssl=true` to this if your PostgreSQL server is using SSL.

### `username`

This is the username to use when connecting.

### `password`

This is the password to use when connecting.

### `maximum-pool-size`

From the [HikariCP documentation](https://github.com/brettwooldridge/HikariCP):

> "This property controls the maximum size that the pool is allowed to reach,
> including both idle and in-use connections. Basically this value will
> determine the maximum number of actual connections to the database backend. A
> reasonable value for this is best determined by your execution environment."

When the pool reaches this size, and no idle connections are available, attempts
to get a connection will wait for `connection-timeout` milliseconds before timing
out.

The default value is 10.

### `conn-max-age`

The maximum time (in minutes) for a pooled connection to remain
unused before it is closed off.

If not supplied, the default value is 60 minutes.

### `conn-lifetime`

The maximum time (in minutes) a pooled connection should remain
open. Any connections older than this setting will be closed
off. Connections currently in use will not be affected until they are
returned to the pool.

If not supplied, we won't terminate connections based on their age
alone.

### `connection-timeout`

The maximum time to wait (in milliseconds) to acquire a connection
from the pool of database connections. If not supplied, defaults to
500.


`[command-processing]` Settings
-----

The `[command-processing]` section configures the command-processing
subsystem.

Every change to PuppetDB's data stores arrives via **commands** that
are inserted into a message queue (MQ). Command processor threads pull
items off of that queue, persisting those changes.

### `threads`

This defines how many command processing threads to use. Each thread
can process a single command at a time. [The number of threads can be
tuned based on what you see in the performance dashboard.][dashboard]

This setting defaults to half the number of cores in your system.

### `concurrent-writes`

This sets a limit on the number of threads that can write to the disk at
any one time. The default value is the smaller number of half the number
of CPU cores and 4.

If your load is low, your disk is fast (i.e. an SSD), and commands
aren't being processed quickly enough, then you could increasing this
value in order to alleviate that, but this is unlikely to be the
bottleneck for command processing.

### `reject-large-commands`

This is a Boolean that enables rejecting (returning an HTTP 413 error)
commands that are too large to process, such as a
catalog that is too large, causing PuppetDB to run out of
memory. This setting can be used along with `max-command-size`.

This setting is false by default.

### `max-command-size`

This is an integer that specifies (in bytes) which commands are "too
large" to process with PuppetDB. By default this setting is a fraction
of the total heap space. It is strongly recommended that users set
this manually as the default is probably too conservative. To help
determine the current size of commands being processed, enable debug
logging for the `puppetlabs.puppetdb.middleware` appender in the
[logback.xml](#logging-config). This setting has no effect when
`reject-large-commands` is set to false.


`[jetty]` (HTTP) settings
-----

The `[jetty]` section configures HTTP for PuppetDB.

> **Note:** If you are using Puppet Enterprise and want to enable the
    PuppetDB dashboard from the PE console, refer to [Enable and view PuppetDB performance dashboard in PE][pe-dashboard]
    for more information. PE users should not edit `jetty.ini`.


### `host`

Sets the IP interface to listen on for **unencrypted** HTTP
traffic. If not supplied, we bind to `localhost`, which will reject
connections from anywhere but the PuppetDB server itself. To listen on
all available interfaces, use `0.0.0.0`.

To avoid DNS resolution confusion, if you wish to set this to something other than `localhost`, we reccomend using an IP address instead of a hostname.

> **Note:** Unencrypted HTTP is the only way to view the [performance
    dashboard][dashboard], because PuppetDB uses host verification for
    SSL. However, it can also be used to make any call to PuppetDB's
    API, including inserting exported resources and retrieving
    arbitrary data about your Puppet-managed nodes. **If you enable
    cleartext HTTP, you MUST configure your firewall to protect
    unverified access to PuppetDB.**

### `port`

Establishes which port to use for **unencrypted** HTTP traffic. If not
supplied, we won't listen for unencrypted traffic at all.

### `max-threads`

Sets the maximum number of threads assigned to responding to HTTP
and HTTPS requests, effectively changing how many concurrent requests
can be made at one time. Defaults to 50.

> **Note:** Due to the behaviour of our web server (Jetty 9), this setting
    must be higher then the number of CPUs on your system or it will
    stop processing any HTTP requests.

### `ssl-host`

Sets which IP interface to listen on for **encrypted** HTTPS traffic. If
not supplied, we bind to `localhost`. To listen on all available
interfaces, use `0.0.0.0`.

To avoid DNS resolution confusion, if you wish to set this to something other than `localhost`, we reccomend using an IP address instead of a hostname

### `ssl-port`

Establishes which port to use for **encrypted** HTTPS traffic. If not
supplied, we won't listen for encrypted traffic at all.

### `ssl-cert`

Sets the path to the server certificate PEM file used by the
PuppetDB web service for HTTPS. During the SSL handshake for a
connection, certificates extracted from this file are presented to the
client for the client's use in validating the server. This file may
contain a single certificate or a chain of certificates ordered from
the end certificate first to the most-root certificate last. For
example, a certificate chain could contain:

* An end certificate.
* An intermediate CA certificate with which the end certificate was issued.
* A root CA certificate with which the intermediate CA certificate was issued.

In the PEM file, the end certificate should appear first, the
intermediate CA certificate should appear second, and the root CA
certificate should appear last.

If a chain is present, it is not required to be complete. If a path
has been specified for the `ssl-cert-chain` setting, the server will
construct the cert chain starting with the first certificate found in
the `ssl-cert` PEM and followed by any certificates in the
`ssl-cert-chain` PEM. In the latter case, any certificates in the
`ssl-cert` PEM beyond the first one are ignored.

### `ssl-key`

This sets the path to the private key PEM file that corresponds with
the `ssl-cert`, if used by the PuppetDB web service for HTTPS.

### `ssl-ca-cert`

This sets the path to the CA certificate PEM file used for client
authentication. Authorized clients must be signed by the CA that corresponds to this certificate.

### `cipher-suites`

Optional. A comma-separated list of cryptographic ciphers to allow for
incoming SSL connections. Valid names are listed in the
[official JVM cryptographic providers documentation](http://docs.oracle.com/javase/8/docs/technotes/guides/security/SunProviders.html#SupportedCipherSuites). Note
that you must use the all-caps cipher suite name.

If not supplied, PuppetDB will use only non-DHE cipher suites.

### `ssl-protocols`

Optional. A comma-separated list of protocols to allow for incoming
SSL connections. Valid names are listed in the
[official JVM cryptographic protocol documentation](http://docs.oracle.com/javase/8/docs/technotes/guides/security/SunProviders.html#SunJSSEProvider). Note
that you must use the names with verbatim capitalization. For example:
`TLSv1, TLSv1.1, TLSv1.2`.

If not supplied, PuppetDB uses a default of `TLSv1, TLSv1.1, TLSv1.2`. By default, SSLv3 is not included in that list due to known vulnerabilities. Users wanting to use SSLv3 need to explicitly specify it in their list.

### `ssl-crl-path`

Optional. This describes a path to a Certificate Revocation List
file. Incoming SSL connections will be rejected if the client
certificate matches a revocation entry in the file.

### `ssl-cert-chain`

This sets the path to a PEM with CA certificates for use in presenting a
client with the server's chain of trust. Certs found in this PEM file are
appended after the first certificate from the `ssl-cert` PEM in the
construction of the certificate chain. This is an optional setting. The
certificates in the `ssl-cert-chain` PEM file should be ordered from the
least-root CA certificate first to the most-root CA certificate last. For
example, a certificate chain could contain:

* An end certificate.
* An intermediate CA certificate with which the end certificate was issued.
* A root CA certificate with which the intermediate CA certificate was issued.

The end certificate should appear in the `ssl-cert` PEM file. In the
`ssl-cert-chain` PEM file, the intermediate CA certificate should appear
first and the root CA certificate should appear last.

The chain is not required to be complete.

> **Note:** This setting overrides the alternate configuration settings
`keystore` and `key-password`.

### `access-log-config`

Optional. This is a path to an XML file containing configuration
information for the `logback-access` module. If present, a logger will
be set up to log information about any HTTP requests Jetty receives
according to the logging configuration, as long as the XML file
pointed to exists and is valid. Information on configuring the
`logback-access` module is available
[here](http://logback.qos.ch/access.html#configuration).

A configuration file may resemble the following:

    <configuration debug="false">
      <appender name="FILE" class="ch.qos.logback.core.FileAppender">
        <file>./dev-resources/access.log</file>
          <encoder>
            <pattern>%h %l %u %user %date "%r" %s %b</pattern>
          </encoder>
        </appender>
        <appender-ref ref="FILE" />
    </configuration>

This example configures a `FileAppender` that outputs to a file,
`access.log`, in the `dev-resources` directory. It will log the remote
host making the request, the log name, the remote user making the
request, the date/time of the request, the URL and method of the
request, the status of the response, and the size in bytes of the
response.

### `graceful-shutdown-timeout`

After receiving a shutdown, this is the number of milliseconds the
server will wait for in-flight requests to complete before actually
shutting down. New requests will be blocked during this time. Defaults
to 30000.

### `request-header-max-size`

This sets the maximum size of an HTTP request header. If a header is
sent that exceeds this value, Jetty will return an HTTP 413 error
response. This defaults to 8192 bytes, and only needs to be configured
if an exceedingly large header is being sent in an HTTP request.

`[nrepl]` settings
-----

The `[nrepl]` section configures remote runtime modification. For
more detailed info, see [our guide to debugging with the remote REPL][repl].

Enabling a remote
[REPL](http://en.wikipedia.org/wiki/Read%E2%80%93eval%E2%80%93print_loop)
allows you to manipulate the behavior of PuppetDB at runtime. This
should only be done for debugging purposes, and is thus disabled by
default. An example configuration stanza:

    [nrepl]
    type = nrepl
    port = 8082
    host = 127.0.0.1

### `enabled`

To enable the REPL, set to true. Defaults to false.

### `port`

The port to use for the REPL.

### `host`

Specifies the host or IP address for the REPL service to listen on. By
default this is `127.0.0.1` only. As this is an insecure channel this
is the only recommended setting for production environments.

If you wish to listen on all interfaces, you can specify `0.0.0.0`, for example, although this is generally not recommended for production.

`[developer]` settings
-----

The `[developer]` section contains configuration items that may be useful to
users developing against the PuppetDB API. These settings may impede
performance, and are not recommended for production use.

### `pretty-print`

Enables/disables default pretty-printing of API responses. Defaults to false.
Enabling default pretty-printing is not recommended in production because it
incurs a penalty in data transfer speed and size. Users may override this
setting on a per-query basis by supplying a `?pretty=` parameter in the URL,
valued `true` or `false`.

`[sync]` settings (Puppet Enterprise only)
-----

The `[sync]` section of the PuppetDB configuration file is used to configure
synchronization for a high-availability system. See
the [HA configuration guide][ha] for complete system configuration instructions.

### `remotes`

The `remotes` configuration key indicates that PuppetDB should poll a remote
PuppetDB server for changes. When it finds changed or updated records on that
server, it will download the records and submit them to the local command queue.

In the configuration file, you specify a `remote` for each server you want to
pull data from. It is perfectly reasonable, and expected, for two servers to
pull data from each other. For each remote, you must provide:

 - The remote server url. This is a root url which should include the protocol
   and port to use (eg. "https://puppetdb.myco.net:8081"). The protocol is
   mandatory and must be either "http" or "https". If the port is not provided,
   it will default to `8080` for http and `8081` for https.

 - The interval at which to poll the remote server for new data. This is
   formatted as a timespan with units (e.g. '2m'). See the
   [node-ttl documentation][node-ttl] for further reference.

You should not configure PuppetDB to sync with itself.

### `entity-time-limit`

Set the maximum time that an entity can sync for (default: `"30m"`). PuppetDB
syncs one entity (`catalogs`, `factsets`, `reports`, and `nodes`) at a time.
While the sync is running it keeps a query open on the PostgreSQL database that
will prevent the removal of old rows. If that connection is open long enough,
it can degrade database performance. You shouldn't need to modify this setting
unless you are experiencing an issue.

### `initial-report-threshold`

PuppetDB's initial sync, which occurs during startup, will only sync reports
newer than `initial-report-threshold` (default: `"0s"`). While starting up,
PuppetDB will not respond to queries or accept command submissions, so this can
be used to get PuppetDB online faster, at the expense that it could return query
responses that are not up to date. Subsequent periodic syncs will transfer the
remaining data.

#### HOCON

If you are using HOCON to configure PuppetDB, use the following structure in
your .conf file:

    sync: {
      remotes: [{server_url: "https://remote-puppetdb.myco.net:8081",
                 interval: 2m}]
    }

#### ini

If you are using a .ini file to configure PuppetDB, use the following structure:

    [sync]
    server_urls = https://remote-puppetdb.myco.net:8081
    intervals = 2m

Multiple values may be provided by comma-separating them, with no whitespace.
You must have exactly the same number of entries in the `server_urls` and
`intervals` values.

Experimental environment variables
-----

> *Note*: these settings are experimental and are likely to be altered
> or removed in a future release.

### `PDB_COMMAND_SQL_STATEMENT_TIMEOUT_MS`

Controls how many milliseconds (by default 10 minutes) PuppetDB will
wait for an SQL command to complete during an attempt to process a
command (store a report, update a factset, etc.).  When set to `0` it
will wait forever (the default before 6.13), and when set to `system`,
it won't specify any timeout, deferring to PostgreSQL's configuration.

### `PDB_GC_DAILY_PARTITION_DROP_LOCK_TIMEOUT_MS`

Controls how many milliseconds (by default 5 minutes) PuppetDB will
wait for the required lock when it attempts to drop a report or
resource event partition that has expired.  When set to `0` it will
wait forever (the default before 6.13), and when set to `system`, it
won't specify any timeout, deferring to PostgreSQL's configuration.

### `PDB_FACT_PATH_GC_SQL_LOCK_TIMEOUT_MS`

Controls how many milliseconds PuppetDB's fact path garbage collection
process will wait for the lock it needs.  When set to `0` it will wait
forever (the default), and when set to `system`, it won't specify
any timeout, deferring to PostgreSQL's configuration.

### `PDB_EXT_INTERRUPT_LINGERING_SYNC_PULL` (Puppet Enterprise only)

Can be set to `true` (the default) or `false`.  When true, will
attempt to interrupt the thread performing sync when the
[`entity-time-limit`](#entity-time-limit) is exceeded during a sync
attempt, in addition to the normal periodic checks for for a timeout.

### `PDB_USE_DEPRECATED_QUERY_STREAMING_METHOD`

When set to `true`, (the default before PuppetDB 7), use the
deprecated query streaming method which retries queries internally
when certain kinds of transient errors are encountered.  The
deprecated method also holds an extra database connection open during
the initial phase of the response.  In newer versions (since 7.0.0),
the default is `false`.

### `PDB_GC_QUERY_BULLDOZER_TIMEOUT_MS`

Controls how many milliseconds (by default 5 minutes) PuppetDB GC
will wait for the query bulldozer thread it spawns to be cleaned
up before logging an error. When set to `0` the query bulldozer
thread is disabled and PuppetDB GC will wait in line to get
the AccessExclusiveLock it needs to drop partitioned tables.
