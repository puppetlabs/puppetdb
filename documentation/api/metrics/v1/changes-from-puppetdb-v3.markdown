---
title: "Metrics API changes in PuppetDB 4"
layout: default
canonical: "/puppetdb/latest/api/metrics/v1/changes-from-puppetdb-v3.html"
---

Most of the metrics' names changed in PuppetDB 4 to standardize with other
Puppet components and provide a more intuitive naming scheme. This document
details the correspondence between the metric names before and after PuppetDB
4.

### Population metrics

```
# Before
puppetlabs.puppetdb.query.population:type=default,name=<misc>
# After
puppetlabs.puppetdb.population:name=<misc>
```

For example, the following metrics:

```
puppetlabs.puppetdb.query.population:type=default,name=num-nodes
puppetlabs.puppetdb.query.population:type=default,name=num-resources
puppetlabs.puppetdb.query.population:type=default,name=avg-resources-per-node
puppetlabs.puppetdb.query.population:type=default,name=pct-resource-dupes
```

are now, respectively:

```
puppetlabs.puppetdb.population:name=num-nodes
puppetlabs.puppetdb.population:name=num-resources
puppetlabs.puppetdb.population:name=avg-resources-per-node
puppetlabs.puppetdb.population:name=pct-resource-dupes
```

### HTTP metrics

Prior to PuppetDB 4 the HTTP metrics were scattered in different namespaces. All
the HTTP metrics are now grouped under the same namespace.

```
# Before
<namespace>:group=<endpoint>,name=<misc>
# After
puppetlabs.puppetdb.http:name=<endpoint>.<misc>
```

Here are some concrete examples:

```
# Before
puppetlabs.puppetdb.http.command:group=/pdb/cmd/v1,name=<status code>
# After
puppetlabs.puppetdb.http:name=/pdb/cmd/v1.<status code>
```

```
# Before
puppetlabs.puppetdb.http.server:group=/pdb/query/v4/catalogs,name=<status code>
puppetlabs.puppetdb.http.server:type=/pdb/query/v4/catalogs,name=service-time
# After
puppetlabs.puppetdb.http:name=/pdb/query/v4/catalogs.<status code>
puppetlabs.puppetdb.http:name=/pdb/query/v4/catalogs.service-time
```

Note that these metrics are now under the same `puppetlabs.puppetdb.http`
namespace.

### Message Queue (MQ) metrics

Command processing metrics related to the message queue
have been moved to the `puppetlabs.puppetdb.mq` namespace.

We now have the following naming conventions:

```
# Before
puppetlabs.puppetdb.command:type=global,name=<misc>
# After
puppetlabs.puppetdb.mq:name=global.<misc>
```

```
# Before
puppetlabs.puppetdb.command:type=<command>.<version>,name=<misc>
# After
puppetlabs.puppetdb.mq:name=<command>.<version>.<misc>
```

For example, the following message queue metrics:

```
puppetlabs.puppetdb.command:type=<name>,name=discarded
puppetlabs.puppetdb.command:type=<name>,name=fatal
puppetlabs.puppetdb.command:type=<name>,name=processed
puppetlabs.puppetdb.command:type=<name>,name=processing-time
puppetlabs.puppetdb.command:type=<name>,name=retried
```

would now be, respectively:

```
puppetlabs.puppetdb.mq:name=<name>.discarded
puppetlabs.puppetdb.mq:name=<name>.fatal
puppetlabs.puppetdb.mq:name=<name>.processed
puppetlabs.puppetdb.mq:name=<name>.processing-time
puppetlabs.puppetdb.mq:name=<name>.retried
```

### Dead Letter Office (DLO) metrics

The metrics for the DLO now have the following structure:

```
# Before
puppetlabs.puppetdb.command.dlo:type=global,name=<misc>
# After
puppetlabs.puppetdb.dlo:name=global.<misc>
```

```
# Before
puppetlabs.puppetdb.command.dlo:type=<filename>,name=<misc>
# After
puppetlabs.puppetdb.dlo:name=<filename>.<misc>
```

### Storage metrics

The storage metrics' names have been shortened with the following convention:

```
# Before
puppetlabs.puppetdb.scf.storage:type=default,name=<misc>
# After
puppetlabs.puppetdb.storage:name=<misc>
```

Here are some some examples:

```
# Before
puppetlabs.puppetdb.scf.storage:type=default,name=duplicate-pct
puppetlabs.puppetdb.scf.storage:type=default,name=gc-time
# After
puppetlabs.puppetdb.storage:type=default,name=duplicate-pct
puppetlabs.puppetdb.storage:type=default,name=gc-time
```
