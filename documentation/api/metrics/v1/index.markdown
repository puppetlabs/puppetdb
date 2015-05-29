---
title: "PuppetDB 2.3 » API » v4 » Querying Metrics"
layout: default
canonical: "/puppetdb/latest/api/metrics/v1/index.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

Querying PuppetDB metrics is accomplished by making an HTTP request
to paths under the `/metrics/v1` REST endpoint.

**Note** In most cases PuppetDB is simply a conduit for mbeans made available
by its components. PuppetDB makes no guarantee about the stability of mbean names.

## Listing available metrics

### Request format

To get a list of all available metric names:

* Request `/metrics/v1/mbeans`.
* Use a `GET` request.

### Response format

Responses return a JSON Object mapping a string to a string:

* The key is the name of a valid MBean
* The value is a URI to use for requesting that MBean's attributes

## Retrieving a specific metric

### Request format

To get the attributes of a particular metric:

* Request `/metrics/v1/mbeans/<name>`, where `<name>` is something that was
  returned in the list of available metrics specified above.
* Use a `GET` request.

### Response format

Responses return a JSON Object mapping strings to (strings/numbers/booleans).

## Useful metrics

### Population metrics

* `puppetlabs.puppetdb.query.population:type=default,name=num-nodes`:
  The number of nodes in your population.
* `puppetlabs.puppetdb.query.population:type=default,name=num-resources`:
  The number of resources in your population.
* `puppetlabs.puppetdb.query.population:type=default,name=avg-resources-per-node`:
  The average number of resources per node in your population.
* `puppetlabs.puppetdb.query.population:type=default,name=pct-resource-dupes`:
  The percentage of resources that exist on more than one node.

### Database metrics

* `com.jolbox.bonecp:type=BoneCP`: Database connection pool
  metrics. How long it takes to get a free connection, average
  execution times, number of free connections, etc.

### Command-processing metrics

Each of the following metrics is available for each command supported
in PuppetDB. In the below list of metrics, `<name>` should be
substituted with a command specifier. Example `<name>`s you can use
include:

* `global`: Aggregate stats for _all_ commands
* `replace catalog.1`: Stats for catalog replacement
* `replace facts.1`: Stats for facts replacement
* `deactivate node.1`: Stats for node deactivation

Other than `global`, all command specifiers are of the form
`<command>.<version>`. As we version commands, you'll be able to get
statistics for each version independently.

Metrics available for each command:

* `puppetlabs.puppetdb.command:type=<name>,name=discarded`: stats
  about commands we've discarded (we've retried them as many times as
  we can, to no avail)
* `puppetlabs.puppetdb.command:type=<name>,name=fatal`: stats about
  commands we failed to process.
* `puppetlabs.puppetdb.command:type=<name>,name=processed`: stats
  about commands we've successfully processed
* `puppetlabs.puppetdb.command:type=<name>,name=processing-time`:
  stats about how long it takes to process commands
* `puppetlabs.puppetdb.command:type=<name>,name=retried`: stats about
  commands that have been submitted for retry (due to transient
  errors)

### HTTP metrics

PuppetDB automatically collects metrics about every URL it has served
to clients. You can see things like the average response time on a
per-url basis, or see how many requests against a particular URL
resulted in a HTTP 400 response code. Each of the following metrics is
available for each URL. The list of automatically generated metrics is
available via the `/metrics/v1/mbeans` endpoint.

Additionally, we also support the following explicit names:

**Note:** The use of these explicit names is deprecated; please use, e.g., `/pdb/cmd/v1` instead.

* `commands`: Stats relating to the command processing REST
  endpoint. The PuppetDB termini in Puppet talk to this endpoint to
  submit new catalogs, facts, etc.
* `metrics`: Stats relating to the metrics REST endpoint. This is the
  endpoint you're reading about right now!
* `facts`: Stats relating to fact querying.
* `resources`: Stats relating to resource querying. This is the
  endpoint used when collecting exported resources.

In addition to customizing `<name>`, the following metrics are
available for each HTTP status code (`<status code>`). For example, you can
see the stats for all `200` responses for the `resources`
endpoint. This allows you to see, per endpoint and per response,
independent counters and statistics.

* `puppetlabs.puppetdb.http.server:type=<name>,name=service-time`:
  stats about how long it takes to service all HTTP requests to this endpoint
* `puppetlabs.puppetdb.http.server:type=<name>,name=<status code>`:
  stats about how often we're returning this response code

### Storage metrics

Metrics involving the PuppetDB storage subsystem all begin with the
`puppetlabs.puppetdb.scf.storage:type=default,name=` prefix. There are
a number of metrics concerned with individual storage operations (storing
resources, storing edges, etc.). Metrics of particular note include:

* `puppetlabs.puppetdb.scf.storage:type=default,name=duplicate-pct`:
  the percentage of catalogs that PuppetDB determines to be
  duplicates of existing catalogs.
* `puppetlabs.puppetdb.scf.storage:type=default,name=gc-time`: state
  about how long it takes to do storage compaction

### JVM Metrics

* `java.lang:type=Memory`: memory usage statistics
* `java.lang:type=Threading`: stats about JVM threads

### MQ Metrics

* `org.apache.activemq:type=Broker,brokerName=localhost,destinationType=Queue,destinationName=puppetlabs.puppetdb.commands`:
  stats about the command processing queue: queue depth, how long messages remain in the queue, etc.

## Example

[Using `curl` from localhost][curl]:

    curl 'http://localhost:8080/metrics/v1/mbeans/java.lang:type=Memory'
