---
layout: default
title: "PuppetDB: Metrics API v2"
canonical: "/puppetdb/latest/api/metrics/v2/jolokia.html"
---

# Metrics API v2

The Jolokia API is enabled by default. You must use `https://` to access `metrics/v2` for any service, and you must present authorization in the form of a Puppet certificate.

## Jolokia endpoints

The v2 metrics endpoint uses the [Jolokia](https://jolokia.org) library, an
extensive open-source metrics library with its own documentation.

The documentation below provides only the information you need to use the metrics
as configured by default for PuppetDB, but Jolokia offers more features than
are described below. Consult the [Jolokia documentation](https://jolokia.org/documentation.html)
for more information.

For security reasons, we enable only the read-access Jolokia interface by default:

-   `read`
-   `list`
-   `version`
-   `search`

### Creating a metrics.conf file
To configure Jolokia metrics, create the `/etc/puppetlabs/puppetdb/conf.d/metrics.conf`
file if one doesn't exist. This file should contain a section like the example shown
below.
```
metrics {
    metrics-webservice: {
        jolokia: {
          #enabled: false # uncomment this line to disable jolokia metrics
            #servlet-init-params: {
                ## Specify a custom security policy:
                ## https://jolokia.org/reference/html/security.html
                #policyLocation: "file:///etc/puppetlabs/puppetdb/jolokia-access.xml"
            #}
        }
    }
}
```

### Configuring Jolokia

To change the security access policy, create the `/etc/puppetlabs/puppetdb/jolokia-access.xml`
file with contents that follow the [Jolokia access policy](https://jolokia.org/reference/html/security.html)
and uncomment the `metrics.metrics-webservice.jolokia.servlet-init-params.policyLocation`
parameter before restarting puppetdb.

The `metrics.metrics-webservice.jolokia.servlet-init-params` table
within the `/etc/puppetlabs/puppetdb/conf.d/metrics.conf` file provides more configuration options. See Jolokia's [agent initialization documentation](https://jolokia.org/reference/html/agents.html#agent-war-init-params) for all of the available options.

### Disabling the endpoints

To disable the v2 endpoints, set the `metrics.metrics-webservice.jolokia.enabled` parameter in `metrics.conf` to `false`.

## Usage

You can query the metrics v2 API using `GET` or `POST` requests.

### `GET /metrics/v2/`

This endpoint requires an operation, and depending on the operation can accept or might require an additional query:

```
GET /metrics/v2/<OPERATION>/<QUERY>
```

#### Response

A successful request returns a JSON document.

#### Examples

To list all valid mbeans querying the metrics endpoint

    GET /metrics/v2/list

Which should return a response similar to

``` json
{
  "request": {
    "type": "list"
  },
  "value": {
    "java.util.logging": {
      "type=Logging": {
        "op": {
          "getLoggerLevel": {
            ...
          },
          ...
        },
        "attr": {
          "LoggerNames": {
            "rw": false,
            "type": "[Ljava.lang.String;",
            "desc": "LoggerNames"
          },
          "ObjectName": {
            "rw": false,
            "type": "javax.management.ObjectName",
            "desc": "ObjectName"
          }
        },
        "desc": "Information on the management interface of the MBean"
      }
    },
    ...
  }
}
```

So, from the example above we could query for the registered logger names with
this HTTP call:

    GET /metrics/v2/read/java.util.logging:type=Logging/LoggerNames

Which would return the JSON document

``` json
{
  "request": {
    "mbean": "java.util.logging:type=Logging",
    "attribute": "LoggerNames",
    "type": "read"
  },
  "value": [
    "javax.management.snmp",
    "global",
    "javax.management.notification",
    "javax.management.modelmbean",
    "javax.management.timer",
    "javax.management",
    "javax.management.mlet",
    "javax.management.mbeanserver",
    "javax.management.snmp.daemon",
    "javax.management.relation",
    "javax.management.monitor",
    "javax.management.misc",
    ""
  ],
  "timestamp": 1497977258,
  "status": 200
}
```

The MBean names can then be created by joining the the first two keys of the
value table with a colon (the `domain` and `prop list` in Jolokia parlance).
Querying the MBeans is achieved via the `read` operation. The `read` operation
has as its GET signature:

    GET /metrics/v2/read/<MBEAN NAMES>/<ATTRIBUTES>/<OPTIONAL INNER PATH FILTER>

### `POST /metrics/v2/<OPERATION>`

You can also submit a POST request with the query as a JSON document in the body of the POST.

## Filtering

The new Jolokia-based metrics API also provides globbing (wildcard selection) and response filtering features.

### Example

You can combine both of these features to query garbage collection data, but return only the collection counts and times.

```
GET metrics/v2/read/java.lang:name=*,type=GarbageCollector/CollectionCount,CollectionTime
```

This returns a JSON response:

``` json
{
  "request": {
    "mbean": "java.lang:name=*,type=GarbageCollector",
    "attribute": [
      "CollectionCount",
      "CollectionTime"
    ],
    "type": "read"
  },
  "value": {
    "java.lang:name=PS Scavenge,type=GarbageCollector": {
      "CollectionTime": 1314,
      "CollectionCount": 27
    },
    "java.lang:name=PS MarkSweep,type=GarbageCollector": {
      "CollectionTime": 580,
      "CollectionCount": 5
    }
  },
  "timestamp": 1497977710,
  "status": 200
}
```

Refer to the
[Jolokia protocol documentation](https://jolokia.org/reference/html/protocol.html)
for more advanced usage.

## Curl example

The jolokia endpoint requires cert-based authentication, which can be done in
curl with the following command.
```
curl https://localhost:8081/metrics/v2/list \
  --cert path/to/localhost.pem \
  --key path/to/localhost.key \
  --cacert path/to/ca.pem
```

Puppet's configuration also has enough information to construct the command for
you. From the appropriate server with a Puppet Agent configured, the following
command should populate the necessary information. For repeated querying, you
should save the output of each command because printing the necessary configs
is _much_ slower than a simple curl command.

```sh
curl "https://$(puppet config print server):8081/metrics/v2/list" \
  --cert "$(puppet config print hostcert)" \
  --key "$(puppet config print hostprivkey)" \
  --cacert "$(puppet config print localcacert)"
```

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

PuppetDB relies on the HikariCP connection pool. The complete list of
HikariCP metrics and their names can be found in
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
available via the `/metrics/v2` endpoint.

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
