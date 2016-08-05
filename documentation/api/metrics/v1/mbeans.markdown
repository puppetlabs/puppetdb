---
title: "PuppetDB 4.2: Metrics endpoint"
layout: default
canonical: "/puppetdb/latest/api/metrics/v1/index.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

Querying PuppetDB metrics is accomplished by making an HTTP request
to paths under the `/metrics/v1` REST endpoint.

>**Note:** In most cases, PuppetDB is simply a conduit for MBeans made available
>by its components. PuppetDB makes no guarantee about the stability of MBean names.

## Listing available metrics

### Request format

To get a list of all available metric names:

* Request `/metrics/v1/mbeans`.
* Use a `GET` request.

### Response format

Responses return a JSON object mapping a string to a string:

* The key is the name of a valid MBean.
* The value is a URI to use for requesting that MBean's attributes.

## Retrieving a specific metric

### Request format

To get the attributes of a particular metric:

* Request `/metrics/v1/mbeans/<name>`, where `<name>` is something that was
  returned in the list of available metrics specified above.
* Use a `GET` request.

### Response format

Responses return a JSON object mapping strings to (strings/numbers/Booleans).

## Useful metrics

### Population metrics

* `puppetlabs.puppetdb.population:name=num-nodes`:
  the number of nodes in your population.
* `puppetlabs.puppetdb.population:name=num-resources`:
  the number of resources in your population.
* `puppetlabs.puppetdb.population:name=avg-resources-per-node`:
  the average number of resources per node in your population.
* `puppetlabs.puppetdb.population:name=pct-resource-dupes`:
  the percentage of resources that exist on more than one node.

### Database Metrics

The deprecated BoneCP connection pooling library has been removed from PuppetDB
and has been replaced with HikariCP. The complete list of HikariCP metrics and
their names can be found in
[their documentation](https://github.com/brettwooldridge/HikariCP/wiki/Dropwizard-Metrics).
All the database metrics have the following naming convention:

```
puppetlabs.puppetdb.database:PDBWritePool.<HikariCP metric>
puppetlabs.puppetdb.database:PDBReadPool.<HikariCP metric>
```

### Message Queue (MQ) metrics

Each of the following metrics is available for each command supported
in PuppetDB. In the following list of metrics, `<name>` should be
substituted with a command specifier. Example substitutes for `<name>` you can use
include:

* `global`: aggregate stats for _all_ commands.
* `replace catalog.1`: stats for catalog replacement.
* `replace facts.1`: stats for facts replacement.
* `deactivate node.1`: stats for node deactivation.

Other than `global`, all command specifiers are of the form
`<command>.<version>`. As we version commands, you'll be able to get statistics
for each version independently.

Metrics available for each command:

* `puppetlabs.puppetdb.mq:name=<name>.discarded`: stats
  about commands we've discarded (we've retried them as many times as
  we can, to no avail).
* `puppetlabs.puppetdb.mq:name=<name>.fatal`: stats about
  commands we failed to process.
* `puppetlabs.puppetdb.mq:name=<name>.processed`: stats
  about commands we've successfully processed
* `puppetlabs.puppetdb.mq:name=<name>.processing-time`:
  stats about how long it takes to process commands
* `puppetlabs.puppetdb.mq:name=<name>.retried`: stats about
  commands that have been submitted for retry (due to transient
  errors).

### HTTP metrics

PuppetDB automatically collects metrics about every URL it has served
to clients. You can see things like the average response time on a
per-URL basis, or see how many requests against a particular URL
resulted in a HTTP 400 response code. Each of the following metrics is
available for each URL. The list of automatically generated metrics is
available via the `/metrics/v1/mbeans` endpoint.

Additionally, we also support the following explicit names:

>**Note:** The use of these explicit names is deprecated; please use, for example, `/pdb/cmd/v1` instead.

* `commands`: stats relating to the command processing REST
  endpoint. The PuppetDB-termini in Puppet talk to this endpoint to
  submit new catalogs, facts, etc.
* `metrics`: stats relating to the metrics REST endpoint. This is the
  endpoint you're reading about right now!
* `facts`: stats relating to fact querying.
* `resources`: stats relating to resource querying. This is the
  endpoint used when collecting exported resources.

In addition to customizing `<name>`, the following metrics are
available for each HTTP status code (`<status code>`). For example, you can
see the stats for all `200` responses for the `resources`
endpoint. This allows you to see, per endpoint and per response,
independent counters and statistics.

* `puppetlabs.puppetdb.http:name=<name>.service-time`:
  stats about how long it takes to service all HTTP requests to this endpoint
* `puppetlabs.puppetdb.http:name=<name>.<status code>`:
  stats about how often we're returning this response code

### Storage metrics

Metrics involving the PuppetDB storage subsystem all begin with the
`puppetlabs.puppetdb.storage:name=` prefix. There are
a number of metrics concerned with individual storage operations (storing
resources, storing edges, etc.). Metrics of particular note include:

* `puppetlabs.puppetdb.storage:name=duplicate-pct`:
  the percentage of catalogs that PuppetDB determines to be
  duplicates of existing catalogs.
* `puppetlabs.puppetdb.storage:name=gc-time`: states
  about how long it takes to do storage compaction.

### JVM metrics

* `java.lang:type=Memory`: memory usage statistics.
* `java.lang:type=Threading`: stats about JVM threads.

### MQ metrics

* `org.apache.activemq:type=Broker,brokerName=localhost,destinationType=Queue,destinationName=puppetlabs.puppetdb.commands`:
  stats about the command processing queue: queue depth, how long messages remain in the queue, etc.

## Example

[Using `curl` from localhost][curl]:

    curl 'http://localhost:8080/metrics/v1/mbeans/puppetlabs.puppetdb.mq%3Aname%3Dglobal.processing-time'
    {
        "EventType" : "calls",
        "OneMinuteRate" : 0.015222994059151214,
        "MeanRate" : 0.05202494702450243,
        "FifteenMinuteRate" : 0.024600100098031683,
        "Max" : 69.326,
        "50thPercentile" : 0.441,
        "Mean" : 4.334236842105263,
        "95thPercentile" : 22.597399999999862,
        "99thPercentile" : 69.326,
        "98thPercentile" : 69.326,
        "Min" : 0.319,
        "999thPercentile" : 69.326,
        "RateUnit" : "SECONDS",
        "75thPercentile" : 1.492,
        "LatencyUnit" : "MILLISECONDS",
        "Count" : 38,
        "StdDev" : 11.981603160418109,
        "FiveMinuteRate" : 0.028576112435005956
    }

    curl 'http://localhost:8080/metrics/v1/mbeans/java.lang:type=Memory'
    {
      "ObjectPendingFinalizationCount" : 0,
      "HeapMemoryUsage" : {
        "committed" : 807403520,
        "init" : 268435456,
        "max" : 3817865216,
        "used" : 129257096
      },
      "NonHeapMemoryUsage" : {
        "committed" : 85590016,
        "init" : 24576000,
        "max" : 184549376,
        "used" : 85364904
      },
      "Verbose" : false,
      "ObjectName" : "java.lang:type=Memory"
    }
