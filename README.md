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
it to modify the behavior of Grayskull, live. Use a section like the
following in your Grayskull config file:

    [repl]
    enabled = true
    port = 8081

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

## License

Copyright (C) 2011 Puppet Labs

No license to distribute or reuse this product is currently available.
For details, contact Puppet Labs.

[leiningen]: https://github.com/technomancy/leiningen
