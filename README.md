# PuppetDB

PuppetDB is a Puppet data warehouse; it manages storage and retrieval
of all platform-generated data, such as catalogs, facts, reports, etc.

So far, we've implemented the following features:

* Fact storage
* Full catalog storage
  * Containment edges
  * Dependency edges
  * Catalog metadata
  * Full resource list with all parameters
  * Node-level tags
  * Node-level classes
* REST Fact retrieval
  * all facts for a given node
* REST Resource querying
  * super-set of storeconfigs query API
  * boolean operators
  * can query for resources spanning multiple nodes and types
* Storeconfigs terminus
  * drop-in replacement of stock storeconfigs code
  * export resources
  * collect resources
  * fully asynchronous operation (compiles aren't slowed down)
  * _much_ faster storage, in _much_ less space
* Inventory service API compatibility
  * query nodes by their fact values
  * drop-in replacement for Dashboard's inventory service

## Componentry

PuppetDB consists of several, cooperating components:

**REST-based command processor**

PuppetDB uses a CQRS pattern for making changes to its domain objects
(facts, catalogs, etc). Instead of simply submitting data to PuppetDB
and having it figure out the intent, the intent needs to be explicitly
codified as part of the operation. This is known as a "command"
(e.g. "replace the current facts for node X").

Commands are processed asynchronously, however we try to do our best
to ensure that once a command has been accepted, it will eventually be
executed. Ordering is also preserved. To do this, all incoming
commands are placed in a message queue which the command processing
subsystem reads from in FIFO order.

Submission of commands is done via HTTP, and documented in the `spec`
directory. There is a specific required wire format for commands, and
failure to conform to that format will result in an HTTP error.

**Storage subsystem**

Currently, PuppetDB's data is stored in a relational database. There
are two supported databases:

* An embedded HSQLDB. This does not require a separate database
  service, and is thus trivial to setup. This database is intended for
  proof-of-concept use; we _do not_ recommend it for long-term
  production use.

* PostgreSQL

There is no MySQL support, as it lacks support for recursive queries
(critical for future graph traversal features).

**REST-based retrieval**

Read-only requests (resource queries, fact queries, etc.) are done
using PuppetDB's REST APIs. Each REST endpoint is documented in the
`spec` directory.

**Remote REPL**

For debugging purposes, you can open up a remote clojure
(REPL)[http://en.wikipedia.org/wiki/Read%E2%80%93eval%E2%80%93print_loop]
and use it to modify the behavior of PuppetDB, live.

Vim support would be a welcome addition; please submit patches!

**Puppet module**

There is a puppet module for automated installation of PuppetDB. It
uses Nginx for SSL termination and reverse proxying.

**Puppet terminuses**

There are a set of Puppet terminuses that acts as a drop-in replacement for
stock storeconfigs functionality. By asynchronously storing catalogs
in PuppetDB, and by leveraging PuppetDB's fast querying, compilation
times are much reduced compared to traditional storeconfigs.

## Keywords in these Documents

The key words "*MUST*", "*MUST NOT*", "*REQUIRED*", "*SHALL*", "*SHALL
NOT*", "*SHOULD*", "*SHOULD NOT*", "*RECOMMENDED*", "*MAY*", and
"*OPTIONAL*" in the PuppetDB docs are to be interpreted as described
in [RFC 2119][RFC2119].

[RFC2119]: http://tools.ietf.org/html/rfc2119

## Installation guide

### Basic Requirements

* JDK 1.6 or above. The built-in Java support on recent versions of
  MacOS X works great, as do the OpenJDK packages available on nearly
  any linux distribution. Or if you like, you can download a recent
  JDK directly from Oracle.

* An existing Puppet infrastrucure. You must first setup a
  Puppetmaster installation, then setup PuppetDB, then configure the
  Puppetmaster to enable _storeconfigs_ and point it at your PuppetDB
  installation.

* A Puppetmaster running Puppet version 2.7.12 or better.

### Storage Requirements

There are 2 currently supported backends for PuppetDB storage:

* PuppetDB's embedded database
* PostgreSQL

The embedded database works well for small deployments (say, less than
100 hosts). It requires no additional daemons or setup, and as such is
very simple to get started with. It supports all PuppetDB features.

However, there is a cost: the embedded database requires a fair amount
of RAM to operate correctly. We'd recommend allocating 1G to PuppetDB
as a starting point. Additionally, the embedded database is somewhat
opaque; unlike more off-the-shelf database daemons, there isn't much
companion tooling for things like interactive SQL consoles,
performance analysis, or backups.

That said, if you have a small installation and enough RAM, then the
embedded database can work just fine.

For most "real" use, we recommend running an instance of
PostgreSQL. Simply install PostgreSQL using a module from the Puppet
Forge or your local package manager, create a new (empty) database for
PuppetDB, and verify that you can login via `psql` to this DB you
just created. Then just supply PuppetDB with the DB host, port, name,
and credentials you've just configured, and we'll take care of the
rest!

### Memory

As mentioned above, if you're using the embedded database we recommend
using 1G or more (to be on the safe side). If you are using an
external database, then a decent rule-of-thumb is to allocate 128M
base + 1M for each node in your infrastructure.

For more detailed RAM requirements, we recommend starting with the
above rule-of-thumb and experimenting. The PuppetDB Web Console shows
you real-time JVM memory usage; if you're constantly hovering around
the maximum memory allocation, then it would be prudent to increase
the memory allocation. If you rarely hit the maximum, then you could
likely lower the memory allocation without incident.

In a nutshell, PuppetDB's RAM usage depends on several factors: how
many nodes you have, how many resources you're managing with Puppet,
and how often those nodes check-in (your `runinterval`). 1000 nodes
that check in once a day will require much less memory than if they
check in every 30 minutes.

The good news is that if you under-provision memory for PuppetDB,
you'll see `OutOfMemoryError` exceptions. However, nothing terrible
should happen; you can simply restart PuppetDB with a larger memory
allocation and it'll pick up where it left off (any requests
successfully queued up in PuppetDB *will* get processed).

So essentially, there's not a slam-dunk RAM allocation that will work
for everyone. But we recommend starting with the rule-of-thumb and
dialing things up or down as necessary, and based on observation of
the running system.

### Large-scale requirements

For truly large installations, we recommend terminating SSL using
Apache or Nginx instead of within PuppetDB itself. This permits much
greater flexibility and control over bandwidth and clients.

## Installation

### Installing from system packages

If installing from a distribution maintained package, such as those
listed on the
[Downloading Puppet Wiki Page](http://projects.puppetlabs.com/projects/puppet/wiki/Downloading_Puppet)
all OS prerequisites should be handled by your package manager. See
the Wiki for information on how to enable repositories for your
particular OS. Usually the latest stable version is available as a
package. If you would like to do puppet-development or see the latest
versions, however, you will want to install from source.

### Installing from source

While we recommend using pre-built packages for production use, it is
occasionally handy to have a source-based installation:

    $ mkdir -p ~/git && cd ~/git
    $ git clone git://github.com/puppetlabs/puppetdb
    $ cd puppetdb
    $ rake install DESTDIR=/opt/puppetdb

You can replace `/opt/puppetdb` with a target installation prefix of
your choosing.

The Puppet terminuses can be installed from source by copying them into the
same directory as your Puppet installation. Using Ruby 1.8 on Linux (without
rvm), this is probably `/usr/lib/ruby/1.8/puppet`.

    $ cp -R puppet/lib/puppet /usr/lib/ruby/1.8/puppet

### Running directly from source

While installing from source is useful for simply running a development version
for testing, for development it's better to be able to run *directly* from
source, without any installation step. This can be accomplished using
[leiningen](https://github.com/technomancy/leiningen#installation), a Clojure build tool.

    # Install leiningen

    # Get the code!
    $ mkdir -p ~/git && cd ~/git
    $ git clone git://github.com/puppetlabs/puppetdb
    $ cd puppetdb

    # Download the dependencies
    $ lein deps

    # Start the server from source. A sample config is provided in the root of
    # the repo, in config.sample.ini
    $ lein run services -c /path/to/config.ini # or to a conf.d directory with ini fragments

From here you can make changes to the code, and trying them out is as easy as
restarting the server.

Other useful commands:

* `lein test` to run the test suite

* `lein docs` to build docs in `docs/uberdoc.html`

To use the Puppet module from source, add the Ruby code to $RUBYLIB.

    $ export RUBYLIB=$RUBYLIB:`pwd`/puppet/lib

Restart the Puppet master each time changes are made to the Ruby code.

### Installing PostgreSQL

While a full discussion of how to install PostgreSQL is outside the
scope of this document, you can get a local copy of PostgreSQL
installed for testing fairly easily.

For example, if you're on a Mac you can install PostgreSQL easily
through Homebrew:

    $ brew install postgresql

    # Now start the database
    $ postgres -D /usr/local/var/postgres &

    # Create a database
    $ createdb puppetdb

On a Debian box you can install PostgreSQL locally like so:

    $ apt-get install postgresql

    # Switch to the postgres user
    $ sudo -u postgres sh

    # Create a new user and database for puppetdb
    $ createuser -DRSP puppetdb
    $ createdb -O puppetdb puppetdb
    $ exit

    # You can now login to the database via TCP with the credentials
    # you just established
    $ psql -h localhost puppetdb puppetdb

## Puppet Setup

In order to talk to PuppetDB, Puppet must be configured to use the PuppetDB
terminuses. This can be achieved by installing the module on the Puppet master
and changing the `storeconfigs_backend` setting to `puppetdb`. The location of
the server can be specified using the `$confdir/puppetdb.conf` file. The basic
format of this file is:

    [main]
    server = puppetdb.example.com
    port = 8080

If no config file is specified, or a value isn't supplied, the defaults will be
used:

    server = puppetdb
    port = 8080

*Additionally*, you will need to specify a "routes" file, which is located by
default at `$confdir/routes.yaml`. The content should be:

    master:
      facts:
        terminus: puppetdb
        cache: yaml

This configuration tells Puppet to use PuppetDB as the authoritative source of
fact information, which is what is necessary for inventory search to consult
it.

## SSL Setup

PuppetDB can do full, verified HTTPS communication between
Puppetmaster and itself. To set this up you need to complete a few
steps:

* Generate a keypair for your PuppetDB instance

* Create a Java _truststore_ containing the CA cert, so we can verify
  client certificates

* Create a Java _keystore_ containing the cert to advertise for HTTPS

* Configure PuppetDB to use the files you just created for HTTPS

The following instructions will use the Puppet CA you've already got
to create the keypair, but you can use any CA as long as you have PEM
format keys and certificates.

For ease-of-explanation, let's assume that:

* you'll be running PuppetDB on a host called `puppetdb.my.net`

* your puppet installation uses the standard directory structure and
  its `ssldir` is `/etc/puppet/ssl`

### Create a keypair

This is pretty easy with Puppet's built-in CA. On the host acting as
your CA (your puppetmaster, most likely):

    # puppet cert generate puppetdb.my.net
    notice: puppetdb.my.net has a waiting certificate request
    notice: Signed certificate request for puppetdb.my.net
    notice: Removing file Puppet::SSL::CertificateRequest puppetdb.my.net at '/etc/puppet/ssl/ca/requests/puppetdb.my.net.pem'
    notice: Removing file Puppet::SSL::CertificateRequest puppetdb.my.net at '/etc/puppet/ssl/certificate_requests/puppetdb.my.net.pem'

Et voilà, you've got a keypair. Copy the following files from your CA
to the machine that will be running PuppetDB:

* `/etc/puppet/ssl/ca/ca_crt.pem`
* `/etc/puppet/ssl/private_keys/puppetdb.my.net.pem`
* `/etc/puppet/ssl/certs/puppetdb.my.net.pem`

You can do that using `scp`:

    # scp /etc/puppet/ssl/ca/ca_crt.pem puppetdb.my.net:/tmp/certs/ca_crt.pem
    # scp /etc/puppet/ssl/private_keys/puppetdb.my.net.pem puppetdb.my.net:/tmp/certs/privkey.pem
    # scp /etc/puppet/ssl/certs/puppetdb.my.net.pem puppetdb.my.net:/tmp/certs/pubkey.pem

The rest of the SSL setup occurs on the PuppetDB host; once you've
copied the aforementioned files over, you don't need to remain logged
in to your CA.

### Create a truststore

On the PuppetDB host, you'll need to use the JDK's `keytool` command
to import your CA's cert into a file format that PuppetDB can
understand.

First, change into the directory where you copied the generated certs
and the CA cert from the previous section. If you copied them to, say,
`/tmp/certs`:

    # cd /tmp/certs

Now use `keytool` to create a _truststore_ file. A _truststore_
contains the set of CA certs to use for validation.

    # keytool -import -alias "My CA" -file ca_crt.pem -keystore truststore.jks
    Enter keystore password:
    Re-enter new password:
    .
    .
    .
    Trust this certificate? [no]:  y
    Certificate was added to keystore

Note that you _must_ supply a password; remember the password you
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

### Create a keystore

Now we can take the keypair you generated for `puppetdb.my.net` and
import it into a Java _keystore_. A _keystore_ file contains
certificate to use during HTTPS. Again, on the PuppetDB host in the
same directory you were using in the previous section:

    # cat privkey.pem pubkey.pem > temp.pem
    # openssl pkcs12 -export -in temp.pem -out puppetdb.p12 -name puppetdb.my.net
    Enter Export Password:
    Verifying - Enter Export Password:
    # keytool -importkeystore  -destkeystore keystore.jks -srckeystore puppetdb.p12 -srcstoretype PKCS12 -alias puppetdb.my.net
    Enter destination keystore password:
    Re-enter new password:
    Enter source keystore password:

You can validate this was correct:

    # keytool -list -keystore keystore.jks
    Enter keystore password:

    Keystore type: JKS
    Keystore provider: SUN

    Your keystore contains 1 entry

    puppetdb.my.net, Mar 30, 2012, PrivateKeyEntry,
    Certificate fingerprint (MD5): 7E:2A:B4:4D:1E:6D:D1:70:A9:E7:20:0D:9D:41:F3:B9

    # puppet cert fingerprint puppetdb.my.net --digest=md5
    MD5 Fingerprint=7E:2A:B4:4D:1E:6D:D1:70:A9:E7:20:0D:9D:41:F3:B9

### Configuring PuppetDB to do HTTPS

Take the _truststore_ and _keystore_ you generated in the preceding
steps and copy them to a directory of your choice. For the sake of
instruction, let's assume you've put them into `/etc/puppetdb/ssl`.

First, change ownership to whatever user you plan on running PuppetDB
as and ensure that only that user can read the files. For example, if
you have a specific `puppetdb` user:

    # chown puppetdb:puppetdb /etc/puppetdb/ssl/truststore.jks /etc/puppetdb/ssl/keystore.jks
    # chmod 400 /etc/puppetdb/ssl/truststore.jks /etc/puppetdb/ssl/keystore.jks

Now you can setup PuppetDB itself. In you PuppetDB configuration
file, make the `[jetty]` section look like so:

    [jetty]
    host = <hostname you wish to bind to for HTTP>
    port = <port you wish to listen on for HTTP>
    ssl-host = <hostname you wish to bind to for HTTPS>
    ssl-port = <port you wish to listen on for HTTPS>
    keystore = /etc/puppetdb/ssl/keystore.jks
    truststore = /etc/puppetdb/ssl/truststore.jks
    key-password = <pw you used when creating the keystore>
    trust-password = <pw you used when creating the truststore>

If you don't want to do unsecured HTTP at all, then you can just leave
out the `host` and `port` declarations. But keep in mind that anyone
that wants to connect to PuppetDB for any purpose will need to be
prepared to give a valid client certificate (even for things like
viewing dashboards and such). A reasonable compromise could be to set
`host` to `localhost`, so that unsecured traffic is only allowed from
the local box. Then tunnels could be used to gain access to
administrative consoles.

That should do it; the next time you start PuppetDB, it will be doing
HTTPS and using the CA's certificate for verifiying clients.

With HTTPS properly setup, the PuppetDB host no longer requires the
PEM files copied over during configuration. Those can be safely
deleted from the PuppetDB box (the "master copies" exist on your CA
host).

## Web Console

Once you have PuppetDB running, visit the following URL on your
PuppetDB host (what port to use depends on your configuration, as
does whether you need to use HTTP or HTTPS):

    /dashboard/index.html?pollingInterval=1000

PuppetDB includes a simple, web-based console that displays a fixed
set of key metrics around PuppetDB operations: memory use, queue
depth, command processing metrics, duplication rate, and REST endpoint
stats.

We display min/max/median of each metric over a configurable duration,
as well as an animated SVG sparkline.

Currently the only way to change the attributes of the dashboard is via URL
parameters:

* width = width of each sparkline
* height = height of each sparkline
* nHistorical = how many historical data points to use in each sparkline
* pollingInterval = how often to poll PuppetDB for updates, in milliseconds

## Configuration guide

PuppetDB is configured using an INI-style file format. The format is
the same that Puppet proper uses for much of its own configuration.

Here is an example configuration file:

    [global]
    vardir = /var/lib/puppetdb
    logging-config = /var/lib/puppetdb/log4j.properties

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //localhost:5432/puppetdb

    [jetty]
    port = 8080

You can specify either a single configuration file or a directory of
.ini files. If you specify a directory (_conf.d_ style) we'll merge
all the .ini files together in alphabetical order.

There's not much to it, as you can see. Here's a more detailed
breakdown of each available section:

**[global]**
This section is used to configure application-wide behavior.

`vardir`

This setting is used as the parent directory for the MQ's data directory. Also,
if a database isn't specified, the default database's files will be stored in
<vardir>/db. The directory must exist and be writable in order for the
application to run.

`logging-config`

Full path to a
[log4j.properties](http://logging.apache.org/log4j/1.2/manual.html)
file. Covering all the options available for configuring log4j is
outside the scope of this document, but the aforementioned link has
some exhaustive information.

For an example log4j.properties file, you can look at the `ext`
directory for versions we include in packages.

If this setting isn't provided, we default to logging at INFO
level to standard out.

You can edit the logging configuration file after you've started
PuppetDB, and those changes will automatically get picked up after a
few seconds.

**[database]**

`gc-interval`

How often, in minutes, to compact the database. The compaction process
reclaims space, and deletes unnecessary rows. If not supplied, the
default is every 60 minutes.

`classname`, `subprotocol`, and `subname`

These are specific to the type of database you're using. We currently
support 2 different configurations:

An embedded database (for proof-of-concept or extremely tiny
installations), and PostgreSQL.

If no database information is supplied, an HSQLDB database at
<vardir>/db will be used.

**Embedded database**

The configuration _must_ look like this:

    classname = org.hsqldb.jdbcDriver
    subprotocol = hsqldb
    subname = file:/path/to/db;hsqldb.tx=mvcc;sql.syntax_pgs=true

Replace `/path/to/db` with a filesystem location in which you'd like
to persist the database.

**PostgreSQL**

The `classname` and `subprotocol` _must_ look like this:

    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //host:port/database

Replace `host` with the hostname on which the database is
running. Replace `port` with the port on which PostgreSQL is
listening. Replace `database` with the name of the database you've
created for use with PuppetDB.

It's possible to use SSL to protect connections to the database. The
[PostgreSQL JDBC docs](http://jdbc.postgresql.org/documentation/head/ssl.html)
indicate how to do this. Be sure to add `ssl=true` to the `subname`
parameter.

Other properties you can set:

`username`

What username to use when connecting.

`password`

A password to use when connecting.

**[command-processing]**

Options relating to the commang-processing subsystem. Every change to
PuppetDB's data stores comes in via commands that are inserted into
an MQ. Command processor threads pull items off of that queue,
persisting those changes.

`threads`

How many command processing threads to use. Each thread can process a
single command at a time. If you notice your MQ depth is rising and
you've got CPU to spare, increasing the number of threads may help
churn through the backlog faster.

If you are saturating your CPU, we recommend lowering the number of
threads.  This prevents other PuppetDB subsystems (such as the web
server, or the MQ itself) from being starved of resources, and can
actually _increase_ throughput.

This setting defaults to half the number of cores in your system.

**[jetty]**

HTTP configuration options.

`host`

The hostname to listen on for _unencrypted_ HTTP traffic. If not
supplied, we bind to localhost.

`port`

What port to use for _unencrypted_ HTTP traffic. If not supplied, we
won't listen for unencrypted traffic at all.

`ssl-host`

The hostname to listen on for HTTPS. If not supplied, we bind to
localhost.

`ssl-port`

What port to use for _encrypted_ HTTPS traffic. If not supplied, we
won't listen for encrypted traffic at all.

`keystore`

The path to a Java keystore file containing the key and certificate we
should use for HTTPS.

`key-password`

Passphrase to use to unlock the keystore file.

`truststore`

The path to a Java keystore file containing the CA certificate(s) for
your puppet infrastructure.

`trust-password`

Passphrase to use to unlock the truststore file.

**[repl]**

Enabling a remote
(REPL)[http://en.wikipedia.org/wiki/Read%E2%80%93eval%E2%80%93print_loop]
allows you to manipulate the behavior of PuppetDB at runtime. This
should only be done for debugging purposes, and is thus disabled by
default. An example configuration stanza:

    [repl]
    enabled = true
    type = nrepl
    port = 8081

`enabled`

Set to `true` to enable the REPL.

`type`

Either `nrepl` or `swank`.

The _nrepl_ repl type opens up a socket you can connect to via
telnet. If you are using emacs' clojure-mode, you can choose a type of
_swank_ and connect your editor directly to a running PuppetDB
instance by using `M-x slime-connect`. Using emacs is much nicer than
using telnet. :)

`port`

What port to use for the REPL.

## Operational information

### Deactivating nodes

A Puppet Face action is provided to "deactivate" nodes. Deactivating
the node will cause it to be excluded from storeconfigs queries, and
it useful if a node no longer exists. The node's data is still
preserved, however, and the node will be reactivated if a new catalog
or facts are received for it.

`puppet node deactivate <node> [<node> ...] --mode master`

This command will submit deactivation commands to PuppetDB for each of
the nodes provided. It's necessary to run this in master mode so that
it can be sure to find the right puppetdb.conf file.

Note that `puppet node destroy` can also be used to deactivate nodes,
as the current behavior of destroy in PuppetDB is to simply
deactivate. However, this behavior may change in future, and the
command is not specific to PuppetDB, so the preferred method is
`puppet node deactivate`.

### Connecting to a remote REPL

If you have configured your PuppetDB instance to start up a remote
REPL, you can connect to it and begin issuing low-level debugging
commands.

For example, let's say that you'd like to perform some emergency
database compaction, and you've got an _nrepl_ type REPL configured on
port 8082:

    $ telnet localhost 8082
    Connected to localhost.
    Escape character is '^]'.
    ;; Clojure 1.3.0
    user=> (+ 1 2 3)
    6

At this point, you're at an interactive terminal that allows you to
manipulate the running PuppetDB instance. This is really a
developer-oriented feature; you have to know both Clojure and the
PuppetDB codebase to make full use of the REPL.

To compact the database, you can just execute the function in the
PuppetDB codebase that performs garbage collection:

    user=> (use 'com.puppetlabs.puppetdb.cli.services)
    nil
    user=> (use 'com.puppetlabs.puppetdb.scf.storage)
    nil
    user=> (use 'clojure.java.jdbc)
    nil
    user=> (with-connection (:database configuration)
             (garbage-collect!))
    (0)

You can also manipulate the running PuppetDB instance by redefining
functions on-the-fly. Let's say that for debugging purposes, you'd
like to log every time a catalog is deleted. You can just redefine
the existing `delete-catalog!` function dynamically:

    user=> (ns com.puppetlabs.puppetdb.scf.storage)
    nil
    com.puppetlabs.puppetdb.scf.storage=>
    (def original-delete-catalog! delete-catalog!)
    #'com.puppetlabs.puppetdb.scf.storage/original-delete-catalog!
    com.puppetlabs.puppetdb.scf.storage=>
    (defn delete-catalog!
      [catalog-hash]
      (log/info (str "Deleting catalog " catalog-hash))
      (original-delete-catalog! catalog-hash))
    #'com.puppetlabs.puppetdb.scf.storage/delete-catalog!

Now any time that function is called, you'll see a message logged.

Note that any changes you make to the running system are transient;
they don't persist between restarts. As such, this is really meant to
be used as a development aid, or as a way of introspecting a running
system for troubleshooting purposes.

[leiningen]: https://github.com/technomancy/leiningen
