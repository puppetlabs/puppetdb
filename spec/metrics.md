# Metrics

Querying PuppetDB metrics is accomplished by making an HTTP request
to paths under the `/metrics` REST endpoint.

# Listing available metrics

## Request format

To get a list of all available metric names:

* Request `/metrics/mbeans`.

* A `GET` request is used.

* There is an `Accept` header containing `application/json`.

## Response format

A JSON Object mapping a string to a string:

* The key is the name of a valid MBean

* The value is a URI to use for requesting that MBean's attributes

# Retrieving a specific metric

## Request format

To get the attributes of a particular metric:

* Request `/metrics/mbean/<name>`, where `<name>` is something
  returned in the list of available metrics specified above.

* A `GET` request is used.

* There is an `Accept` header containing `application/json`.

## Response format

A JSON Object mapping strings to (strings/numbers/booleans).

# Example

You can use `curl` to grab metrics like so:

    curl -v -H "Accept: application/json" 'http://localhost:8080/metrics/mbean/java.lang:type=Memory'

# Useful metrics

## Population metrics

* `com.puppetlabs.puppetdb.query.population:type=default,name=num-nodes`:
  The number of nodes in your population.

* `com.puppetlabs.puppetdb.query.population:type=default,name=num-resources`:
  The number of resources in your population.

* `com.puppetlabs.puppetdb.query.population:type=default,name=avg-resources-per-node`:
  The average number of resources per node in your population.

* `com.puppetlabs.puppetdb.query.population:type=default,name=pct-resource-dupes`:
  The percentage of resources that exist on more than one node.

## Database metrics

* `com.jolbox.bonecp:type=BoneCP`: Database connection pool
  metrics. How long it takes to get a free connection, average
  execution times, number of free connections, etc.

## Command-processing metrics

Each of the following metrics is available for each command supported
in PuppetDB. In the below list of metrics, `<name>` should be
substituted with a command specifier. Example `<name>`s you can use
include:

* `global`: Aggregate stats for _all_ commands
* `replace catalog.1`: Stats for catalog replacement
* `replace facts.1`: Stats for catalog replacement

Other than `global`, all command specifiers are of the form
`<command>.<version>`. As we version commands, you'll be able to get
statistics for each version independently.

Metrics available for each command:

* `com.puppetlabs.puppetdb.command:type=<name>,name=discarded`: stats
  about commands we've discarded (we've retried them as many times as
  we can, to no avail)

* `com.puppetlabs.puppetdb.command:type=<name>,name=fatal`: stats about
  commands we failed to process.

* `com.puppetlabs.puppetdb.command:type=<name>,name=processed`: stats
  about commands that we've successfully processed

* `com.puppetlabs.puppetdb.command:type=<name>,name=processing-time`:
  stats about how long it takes to process commands

* `com.puppetlabs.puppetdb.command:type=<name>,name=retried`: stats about
  commands that have been submitted for retry (due to transient
  errors)

## HTTP metrics

Each of the following metrics is available for each HTTP endpoint. In
the below list of metrics, `<name>` should be substituted with a REST
endpoint name. Example `<name>`s you can use include:

* `commands`: Stats relating to the command processing REST
  endpoint. The PuppetDB terminus in Puppet talks to this endpoint to
  submit new catalogs, facts, etc.

* `metrics`: Stats relating to the metrics REST endpoint. This is the
  endpoint you're reading about right now!

* `facts`: Stats relating to fact querying. This is the endpoint used
  by the puppetmaster for inventory service queries.

* `resources`: Stats relating to resource querying. This is the
  endpoint used when collecting exported resources.

In addition to customizing `<name>`, the following metrics are
available for each HTTP status code (`<status code>`). For example, you can
see the stats for all `200` responses for the `resources`
endpoint. This allows you to see, per endpoint and per response,
independent counters and statistics.

* `com.puppetlabs.puppetdb.http.server:type=<name>,name=service-time`:
  stats about how long it takes to service all HTTP requests to this endpoint

* `com.puppetlabs.puppetdb.http.server:type=<name>,name=<status code>`:
  stats about how often we're returning this response code

## Storage metrics

Metrics involving the PuppetDB storage subsystem all begin with the
`com.puppetlabs.puppetdb.scf.storage:type=default,name=` prefix. There are
a number of metrics around individual storage operations (storing
resources, storing edges, etc.). Metrics of particular note include:

* `com.puppetlabs.puppetdb.scf.storage:type=default,name=duplicate-pct`:
  the percentage of catalogs that PuppetDB determines to be
  duplicates of existing catalogs.

* `com.puppetlabs.puppetdb.scf.storage:type=default,name=gc-time`: state
  about how long it takes to do storage compaction

## JVM Metrics

* `java.lang:type=Memory`: memory usage statistics

* `java.lang:type=Threading`: stats about JVM threads

## MQ Metrics

* `org.apache.activemq:BrokerName=localhost,Type=Queue,Destination=com.puppetlabs.puppetdb.commands`:
  stats about the command processing queue. Queue depth, stats around
  how long messages remain in the queue, etc.

