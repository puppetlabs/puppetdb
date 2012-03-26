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

## Usage

* Install [leiningen][leiningen].

* `lein deps`, to download dependencies

* `lein test`, to run the test suite

* `lein marg`, to generate docs

* `lein uberjar`, to build a standalone artifact

* Create a configuration file appropriate for your database
choice. Look at resources/config.ini for an example configuration
file.

* `java -jar *standalone.jar services -h`

## Web Console

Once you have Grayskull running, visit:

    http://your-grayskull-host:your-grayskull-port/dashboard/index.html?pollingInterval=1000

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

## Configuration

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

The hostname to listen on. If not supplied, we bind to all interfaces.

`port`

The port to listen on. If not supplied, default to 80.

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
