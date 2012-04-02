# The Grayskull project

Grayskull is a Puppet data warehouse; it manages storage and retrieval
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

Grayskull consists of several, cooperating components:

**REST-based command processor**

Grayskull uses a CQRS pattern for making changes to its domain objects
(facts, catalogs, etc). Instead of simply submitting data to Grayskull
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

Currently, Grayskull's data is stored in a relational database. There
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
using Grayskull's REST APIs. Each REST endpoint is documented in the
`spec` directory.

**Remote REPL**

For debugging purposes, you can open up a remote clojure REPL and use
it to modify the behavior of Grayskull, live.

Vim support would be a welcome addition; please submit patches!

**Puppet module**

There is a puppet module for automated installation of Grayskull. It
uses Nginx for SSL termination and reverse proxying.

**Puppet Terminus**

There is a puppet terminus that acts as a drop-in replacement for
stock storeconfigs functionality. By asynchronously storing catalogs
in Grayskull, and by leveraging Grayskull's fast querying, compilation
times are much reduced compared to traditional storeconfigs.

## Keywords in these Documents

The key words "*MUST*", "*MUST NOT*", "*REQUIRED*", "*SHALL*", "*SHALL
NOT*", "*SHOULD*", "*SHOULD NOT*", "*RECOMMENDED*", "*MAY*", and
"*OPTIONAL*" in the Grayskull docs are to be interpreted as described
in [RFC 2119][RFC2119].

[RFC2119]: http://tools.ietf.org/html/rfc2119

## Building the project

* Install [leiningen][leiningen].

* `lein deps`, to download dependencies

* `lein test`, to run the test suite

* `lein marg`, to generate docs

* `lein uberjar`, to build a standalone artifact

## Installation guide

### Basic Requirements

* JDK 1.6 or above. The built-in Java support on recent versions of
  MacOS X works great, as do the OpenJDK packages available on nearly
  any linux distribution. Or if you like, you can download a recent
  JDK directly from Oracle.

* An existing Puppet infrastrucure. You must first setup a
  Puppetmaster installation, then setup Grayskull, then configure the
  Puppetmaster to enable _storeconfigs_ and point it at your Grayskull
  installation.

### Storage Requirements

There are 2 currently supported backends for Grayskull storage:

* Grayskull's embedded database
* PostgreSQL

The embedded database works well for small deployments (say, less than
100 hosts). It requires no additional daemons or setup, and as such is
very simple to get started with. It supports all Grayskull features.

However, there is a cost: the embedded database requires a fair amount
of RAM to operate correctly. We'd recommend allocating 1G to Grayskull
as a starting point. Additionally, the embedded database is somewhat
opaque; unlike more off-the-shelf database daemons, there isn't much
companion tooling for things like interactive SQL consoles,
performance analysis, or backups.

That said, if you have a small installation and enough RAM, then the
embedded database can work just fine.

For most "real" use, we recommend running an instance of
PostgreSQL. Simply install PostgreSQL using a module from the Puppet
Forge or your local package manager, create a new (empty) database for
Grayskull, and verify that you can login via `psql` to this DB you
just created. Then just supply Grayskull with the DB host, port, name,
and credentials you've just configured, and we'll take care of the
rest!

### Memory

As mentioned above, if you're using the embedded database we recommend
using 1G or more (to be on the safe side). If you are using an
external database, then a decent rule-of-thumb is to allocate 128M
base + 1M for each node in your infrastructure.

For more detailed RAM requirements, we recommend starting with the
above rule-of-thumb and experimenting. The Grayskull Web Console shows
you real-time JVM memory usage; if you're constantly hovering around
the maximum memory allocation, then it would be prudent to increase
the memory allocation. If you rarely hit the maximum, then you could
likely lower the memory allocation without incident.

In a nutshell, Grayskull's RAM usage depends on several factors: how
many nodes you have, how many resources you're managing with Puppet,
and how often those nodes check-in (your `runinterval`). 1000 nodes
that check in once a day will require much less memory than if they
check in every 30 minutes.

The good news is that if you under-provision memory for Grayskull,
you'll see `OutOfMemoryError` exceptions. However, nothing terrible
should happen; you can simply restart Grayskull with a larger memory
allocation and it'll pick up where it left off (any requests
successfully queued up in Grayskull *will* get processed).

So essentially, there's not a slam-dunk RAM allocation that will work
for everyone. But we recommend starting with the rule-of-thumb and
dialing things up or down as necessary, and based on observation of
the running system.

### Large-scale requirements

For truly large installations, we recommend terminating SSL using
Apache or Nginx instead of within Grayskull itself. This permits much
greater flexibility and control over bandwidth and clients.

## SSL Setup

Grayskull can do full, verified HTTPS communication between
Puppetmaster and itself. To set this up you need to complete a few
steps:

* Generate a keypair for your Grayskull instance

* Create a Java _truststore_ containing the CA cert, so we can verify
  client certificates

* Create a Java _keystore_ containing the cert to advertise for HTTPS

* Configure Grayskull to use the files you just created for HTTPS

The following instructions will use the Puppet CA you've already got
to create the keypair, but you can use any CA as long as you have PEM
format keys and certificates.

For ease-of-explanation, let's assume that:

* you'll be running Grayskull on a host called `grayskull.my.net`

* your puppet installation uses the standard directory structure and
  its `vardir` is `/var/lib/puppet`

### Create a keypair

This is pretty easy with Puppet's built-in CA:

    # puppet cert generate grayskull.my.net
    notice: grayskull.my.net has a waiting certificate request
    notice: Signed certificate request for grayskul.my.net
    notice: Removing file Puppet::SSL::CertificateRequest grayskull.my.net at '/var/lib/puppet/ssl/ca/requests/grayskull.my.net.pem'
    notice: Removing file Puppet::SSL::CertificateRequest grayskull.my.net at '/var/lib/puppet/ssl/certificate_requests/grayskull.my.net.pem'

Et voilà, you've got a keypair.

### Create a truststore

You'll need to use the JDK's `keytool` command to import your CA's
cert into a file format that Grayskull can understand:

    # keytool -import -alias "My CA" -file /var/lib/puppet/ssl/ca/ca_crt.pem -keystore truststore.jks
    Enter keystore password:
    Re-enter new password:
    .
    .
    .
    Trust this certificate? [no]:  y
    Certificate was added to keystore

Note that you _must_ supply a password; remember the password you
used, as you'll need it to configure Grayskull later. Once imported,
you can view your certificate:

    # keytool -list -keystore truststore.jks
    Enter keystore password:

    Keystore type: JKS
    Keystore provider: SUN

    Your keystore contains 1 entry

    my ca, Mar 30, 2012, trustedCertEntry,
    Certificate fingerprint (MD5): 99:D3:28:6B:37:13:7A:A2:B8:73:75:4A:31:78:0B:68

Note the MD5 fingerprint; you can use it to verify this is the correct cert:

    # openssl x509 -in /var/lib/puppet/ssl/ca/ca_crt.pem -fingerprint -md5
    MD5 Fingerprint=99:D3:28:6B:37:13:7A:A2:B8:73:75:4A:31:78:0B:68

### Create a keystore

Now we can take the keypair you generated for `grayskull.my.net` and
import it into a Java _keystore_:

    # cat /var/lib/puppet/ssl/private_keys/grayskull.my.net.pem /var/lib/puppet/ssl/certs/grayskull.my.net.pem > temp.pem
    # openssl pkcs12 -export -in temp.pem -out activemq.p12 -name grayskull.my.net
    Enter Export Password:
    Verifying - Enter Export Password:
    # keytool -importkeystore  -destkeystore keystore.jks -srckeystore activemq.p12 -srcstoretype PKCS12 -alias grayskull.my.net
    Enter destination keystore password:
    Re-enter new password:
    Enter source keystore password:

You can validate this was correct:

    # keytool -list -keystore keystore.jks
    Enter keystore password:

    Keystore type: JKS
    Keystore provider: SUN

    Your keystore contains 1 entry

    grayskull.my.net, Mar 30, 2012, PrivateKeyEntry,
    Certificate fingerprint (MD5): 7E:2A:B4:4D:1E:6D:D1:70:A9:E7:20:0D:9D:41:F3:B9

    # puppet cert fingerprint grayskull.my.net --digest=md5
    MD5 Fingerprint=7E:2A:B4:4D:1E:6D:D1:70:A9:E7:20:0D:9D:41:F3:B9

### Configuring Grayskull to do HTTPS

Take the _truststore_ and _keystore_ you generated in the preceding
steps and copy them to a directory of your choice. For the sake of
instruction, let's assume you've put them into `/etc/grayskull/ssl`.

First, change ownership to whatever user you plan on running Grayskull
as and ensure that only that user can read the files. For example, if
you have a specific `grayskull` user:

    # chown grayskull:grayskull /etc/grayskull/ssl/truststore.jks /etc/grayskull/ssl/keystore.jks
    # chmod 400 /etc/grayskull/ssl/truststore.jks /etc/grayskull/ssl/keystore.jks

Now you can setup Grayskull itself. In you Grayskull configuration
file, make the `[jetty]` section look like so:

    [jetty]
    host = <hostname you wish to bind to for HTTP>
    port = <port you wish to listen on for HTTP>
    ssl-host = <hostname you wish to bind to for HTTPS>
    ssl-port = <port you wish to listen on for HTTPS>
    keystore = /etc/grayskull/ssl/keystore.jks
    truststore = /etc/grayskull/ssl/truststore.jks
    key-password = <pw you used when creating the keystore>
    trust-password = <pw you used when creating the truststore>

If you don't want to do unsecured HTTP at all, then you can just leave
out the `host` and `port` declarations. But keep in mind that anyone
that wants to connect to Grayskull for any purpose will need to be
prepared to give a valid client certificate (even for things like
viewing dashboards and such). A reasonable compromise could be to set
`host` to `localhost`, so that unsecured traffic is only allowed from
the local box. Then tunnels could be used to gain access to
administrative consoles.

That should do it; the next time you start Grayskull, it will be doing
HTTPS and using the CA's certificate for verifiying clients.

## Web Console

Once you have Grayskull running, visit the following URL on your
Grayskull host (what port to use depends on your configuration, as
does whether you need to use HTTP or HTTPS):

    /dashboard/index.html?pollingInterval=1000

Grayskull includes a simple, web-based console that displays a fixed
set of key metrics around Grayskull operations: memory use, queue
depth, command processing metrics, duplication rate, and REST endpoint
stats.

We display min/max/median of each metric over a configurable duration,
as well as an animated SVG sparkline.

Currently the only way to change the attributes of the dashboard is via URL
parameters:

* width = width of each sparkline
* height = height of each sparkline
* nHistorical = how many historical data points to use in each sparkline
* pollingInterval = how often to poll Grayskull for updates, in milliseconds

## Configuration guide

Grayskull is configured using an INI-style file format. The format is
the same that Puppet proper uses for much of its own configuration.

Here is an example configuration file:

    [logging]
    configfile = /var/lib/grayskull/log4j.properties

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //localhost:5432/grayskull

    [mq]
    dir = /var/lib/grayskull/mq

    [jetty]
    port = 8080

There's not much to it, as you can see. Here's a more detailed
breakdown of each available section:

**[logging]**

This section is optional. If there's no `[logging]` section in the
configuration file, we default to logging at INFO level to standard
out.

`configfile`

Full path to a
[log4j.properties](http://logging.apache.org/log4j/1.2/manual.html)
file. Covering all the options available for configuring log4j is
outside the scope of this document, but the aforementioned link has
some exhaustive information.

For an example log4j.properties file, you can look at
[the one we use as a default in Grayskull](https://github.com/grimradical/puppet-grayskull/blob/master/resources/log4j.properties).

You can edit the logging configuration file after you've started
Grayskull, and those changes will automatically get picked up after a
few seconds.

**[database]**

`classname`, `subprotocol`, and `subname`

These are specific to the type of database you're using. We currently
support 2 different configurations:

An embedded database (for proof-of-concept or extremely tiny
installations), and PostgreSQL.

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
created for use with Grayskull.

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
Grayskull's data stores comes in via commands that are inserted into
an MQ. Command processor threads pull items off of that queue,
persisting those changes.

`threads`

How many command processing threads to use. Each thread can process a
single command at a time. If you notice your MQ depth is rising and
you've got CPU to spare, increasing the number of threads may help
churn through the backlog faster.

If you are saturating your CPU, we recommend lowering the number of
threads.  This prevents other Grayskull subsystems (such as the web
server, or the MQ itself) from being starved of resources, and can
actually _increase_ throughput.

This setting defaults to half the number of cores in your system.

**[mq]**

Message queue configuration options.

`dir`

What directory to use to persist the message queue (because that stuff
is important!). If the directory doesn't exist, it will be created
automatically upon startup.

**[jetty]**

HTTP configuration options.

`host`

The hostname to listen on for _unencrypted_ HTTP traffic. If not
supplied, we bind to all interfaces.

`port`

What port to use for _unencrypted_ HTTP traffic. If not supplied, we
won't listen for unencrypted traffic at all.

`ssl-host`

The hostname to listen on for HTTPS. If not supplied, we bind to all
interfaces.

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

Enabling a remote REPL allows you to manipulate the behavior of
Grayskull at runtime. This should only be done for debugging purposes,
and is thus disabled by default. An example configuration stanza:

    [repl]
    enabled = true
    type = nrepl
    port = 8081

`enabled`

Set to `true` to enable the REPL.

`type`

Either `nrepl` or `swank`.

The _nrepl_ repl type opens up a socket you can connect to via telnet. If you
are using emacs' clojure-mode, you can choose a type of _swank_ and connect
your editor directly to a running Grayskull instance by using `M-x
slime-connect`. Using emacs is much nicer than using telnet. :)

`port`

What port to use for the REPL.

## Operational information

TODO: need moar docz here

## License

Copyright (C) 2011 Puppet Labs

No license to distribute or reuse this product is currently available.
For details, contact Puppet Labs.

[leiningen]: https://github.com/technomancy/leiningen
