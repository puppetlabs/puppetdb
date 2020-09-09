---
title: "Metrics endpoint"
layout: default
canonical: "/puppetdb/latest/api/metrics/v1/index.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[CVE-2020-7943]: https://puppet.com/security/cve/CVE-2020-7943/

Querying PuppetDB metrics is accomplished by making an HTTP request
to paths under the `/metrics/v1` REST endpoint. The metrics v1 API
is disabled by default and is deprecated.  Please prefer [the `/metrics/v2` endpoint](../v2/jolokia.html).

>**Note:** The `metrics/v1` endpoint has been disabled by default due to a CVE
>described in [CVE-2020-7943][CVE-2020-7943]. You can re-enable this endpoint if
>desired by following the instructions in the [Re-enable metrics v1 endpoint](#re-enable-metrics-v1-endpoint)
>section below.

>**Note:** In most cases, PuppetDB is simply a conduit for MBeans made available
>by its components. PuppetDB makes no guarantee about the stability of MBean names.

## Re-enable metrics v1 endpoint
The v1 metrics endpoint is disabled by default due to a CVE described in [CVE-2020-7943][CVE-2020-7943].

To re-enable this endpoint create a `metrics.conf` file in the `/etc/puppetlabs/puppetdb/conf.d/`
directory or wherever your PuppetDB `conf.d/` directory lives. This file should contain
the following content.
```
metrics: {
    metrics-webservice: {
        mbeans: {
            enabled: true
        }
    }
}
```
A PuppetDB service restart will be required to re-enable the endpoint.

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

## Bulk retrieving metrics

### Request format

Multiple metrics can be retrieved in a single call by POSTing a JSON
array to the mbeans endpoint. An example is below:

    curl -X POST \
       -H "Content-Type: application/json" \
       -d '["puppetlabs.puppetdb.storage:name=replace-facts-time", "puppetlabs.puppetdb.storage:name=replace-catalog-time"]' \
       http://localhost:8080/metrics/v1/mbeans

### Response format

An array of JSON objects are returned. The results are in the order
they were provided in the POSTed array. The JSON objects are the
same as if they were individually retrieved via the `mbean/<name>`
endpoint.

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

### Message queue metrics

PuppetDB maintains various command processing metrics, all computed
with respect to the last restart.  There are `global` statistics,
aggregated across all commands, and individual statistics, computed
for each version of each command.

#### Global metrics

Each of these metrics can be accessed as
`puppetlabs.puppetdb.mq:name=global.<item>`, using any of the
following `<item>`s:

* `seen`: meter measuring commands received (valid or invalid)
* `processed`: meter measuring commands successfully processed
* `fatal`: meter measuring fatal processing errors
* `retried`: meter measuring commands scheduled for retrial
* `awaiting-retry`: number of commands waiting to be retried
* `retry-counts`: histogram of retry counts (until success or discard)
* `discarded`: meter measuring commands discarded as invalid
* `processing-time`: timing statistics for the processing of
  previously enqueued commands
* `queue-time`: histogram of the time commands have spent waiting in the queue
* `depth`: number of currently enqueued commands
* `ignored`: number of obsolete commands that have been ignored
* `size`: histogram of submitted command sizes (i.e. HTTP Content-Lengths)

For example: `puppetlabs.puppetdb.mq:name=global.seen`.

#### Metrics for each command version

Each of the command-specific metrics can be accessed as
`puppetlabs.puppetdb.mq:name=<command>.<version>.<item>`, where
`<command>` must be a valid command name, `<version>` must be the
integer command version, and `<item>` must be one of the following:

* `seen`: meter measuring commands received (valid or invalid)
* `processed`: meter measuring commands successfully processed
* `fatal`: meter measuring fatal processing errors
* `retried`: meter measuring commands scheduled for retrial
* `retry-counts`: histogram of retry counts (until success or discard)
* `discarded`: meter measuring commands discarded as invalid
* `ignored`: number of obsolete commands that have been ignored
* `processing-time`: timing statistics for the processing of
  previously enqueued commands

For example: `puppetlabs.puppetdb.mq:name=replace catalog.9.processed`.

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
