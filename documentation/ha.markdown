---
title: "High Availability (HA)"
layout: default
---

[configure]: ./configure.html
[pe-configure]: ./pe-configure.html
[logging]: ./logging.html
[metrics]: ./api/metrics/v1/index.html

Configuring PuppetDB for High Availability
-----

In Puppet Enterprise 2016.5 and later, PuppetDB may be configured for high
availability in order to withstand network partitions or system failure.

PuppetDB is automatically configured for high availability as part of an HA
deployment of Puppet Enterprise. For more information about high availability in
Puppet Enterprise, see [High availability overview](https://puppet.com/docs/pe/2018.1/high_availability_overview.html).

HA Overview
-----

PuppetDB HA has two parts: first, Puppet Server is configured to send commands
and queries to multiple PuppetDB servers. Second, those servers are configured
to periodically reconcile their differences, transferring any records which they
are missing. This process is pull-based and runs on a configurable interval.

PuppetDB replication is in principle a multi-master system. You can issue
commands to any server, and they can be reconciled with any other server without
conflicts. But, in order to minimize confusion when using exported resources, we
recommend that only one node be conventionally treated as
primary. [See below](#exportedResources) for further discussion.

PuppetDB HA is available in Puppet Enterprise 2016.5 and later.

Manual Installation and Configuration
----

1. Provision two PuppetDB nodes. Designate one of these as your _primary_
   PuppetDB, and the other as the _replica_. Be sure that each PuppetDB is using
   its own PostgreSQL cluster, because HA doesn't buy you anything if you have
   single database. In this guide, we will give them the hostnames
   `primary-puppetdb.mycorp.net` and `replica-puppetdb.mycorp.net`.

2. Configure each PuppetDB to pull from the other. This guide will use
   HOCON-style configuration. See the
   [PE Configuration Documentation][pe-configure] for further details.

  a. Configure primary-puppetdb to pull from replica-puppetdb by placing this in its
     config file:

         [sync]
         server_urls = https://replica-puppetdb.mycorp.net:8081
         intervals = 2m

  b. Configure replica-puppetdb to pull from primary-puppetdb:

         [sync]
         server_urls = https://primary-puppetdb.mycorp.net:8081
         intervals = 2m

3. Configure the Puppet master to point to both of your PuppetDB instances. In
   your puppetdb.conf, use this as your server configuration (in the 'main' section):

        server_urls = https://primary-puppetdb.mycorp.net:8081,https://replica-puppetdb.mycorp.net:8081
        sticky_read_failover = true
        command_broadcast = true

   It is important that your primary PuppetDB appear first in this list, so it
   will always be tried first when submitting new data from the Puppet master.

4. Restart your PuppetDBs and your Puppet master.

Deployment tips
----

- Each PuppetDB server should be configured to use a separate PostgreSQL
  instance.

- If you are using multiple compile masters, be sure that their clocks are in
  sync (using NTP, for example). PuppetDB relies on timestamps for discerning
  which data is newer for any given node, so the clocks on your compile masters
  need to be within `runinterval` of each other.

- The timeout for HTTP connections made by Puppet Server, including those to
  PuppetDB, can be configured using the
  `http-client.connect-timeout-milliseconds` key. This defaults to 120 seconds,
  which is a good value for communications with single services. But for
  PuppetDB HA to work well, you should use a considerably smaller timeout. The
  exact value will depend on your infrastructure, but 10 seconds is a good place
  to start. A catalog compilation can require as many as 4 timeouts, so be sure
  that you choose a value that is less that 25% of the timeout configured on any
  upstream load balancers. See
  the
  [Puppet Server configuration documentation]({{puppetserver}}/config_file_puppetserver.html) for
  details.

FAQ
-----

Q: *Why not just use PostgreSQL streaming replication?*

A: The principal goal of PuppetDB's HA solution is to ensure that Puppet runs
can succeed despite server outages and intermittent network connections. The
replica must be writable for this, and PostgreSQL streaming replication only
provides a read-only replica. If you need such a replica for performance
reasons, then streaming replication works very well.

There are other replication solutions available for PostgreSQL that do provide
write availability. Some of these use a master election system to choose which
database is writable. This can work, but it tends to be difficult to deploy and
operate.

Others accept writes to all databases, recording conflicts for later resolution
by the application. For PuppetDB, handling such conflicts at the database level
would be very complex. Conversely, at the application level we can treat the
entities as they are treated through the rest of the Puppet stack (e.g. a whole
catalog vs. the many database rows required to store its resources). This
facilitates simple, deterministic conflict resolution.


Q: <a name="exportedResources"></a>*What's the deal with exported resources?*

A: Exported resources are a great feature, but you need to be careful when using
them. In particular: because PuppetDB is *eventually* consistent, changes to
exported resources will *eventually* be visible on other nodes (not
immediately). This is true in any PuppetDB installation, but there is an added
subtlety which you should be aware of when using an HA configuration:

In failover scenarios, exported resources may appear to go back in time. This
can happen if commands have been written to your primary PuppetDB but haven't yet
been copied to the replica. If the primary PuppetDB becomes unreachable
in this state, exported resource queries will be redirected to the replica,
which does not yet have the latest data.

This problem is significantly mitigated by using the command_broadcast flag in
puppetdb.conf, as recommended above. By pushing all data to the replica PuppetDB
when doing the initial write, the replica should nearly always be up to date,
but some failure cases still exist (for instance, intermittent connectivity to a
replica followed by immediately losing the primary). Because of this, we don't
recommend using PuppetDB HA in conjunction with exported resources for sensitive
applications where the data is changing often. For typical applications, such as
adding newly provisioned nodes to a load balancer's pool or registering them
with a monitoring system, small state regressions will have very low impact.

Q: *What durability guarantees does PuppetDB HA offer?*

A: In the recommended configuration described above, there are corner cases
which allow data loss. If you want to be absolutely certain that all data is
stored on at least 2 (or more) PuppetDB servers, you can put this in your
puppetdb.conf:

        min_successful_submissions = 2

With this configuration, a Puppet run will fail if any data cannot be written to
at least two PuppetDB servers. This may be useful if you have three PuppetDB
servers, for example.

Operations
-----

PuppetDB provides several facilities to help you monitor the state of
replication:

## Structured Logging

HA-related events are written to a log named ":sync". You can configure the
handling of these events to fit your requirements.

If you have configured structured logging as described in the
[Logging Configuration Guide][logging], you will see additional attributes on each JSON log message.

### Common fields

* `phase`: The sync is divided into nested phases: `"sync"`, `"entity"`, `"record"`, and
  `"deactivate"`. These are described in more detail below.

* `event`: All sync log messages have an event field, indicating its position
  within the phase. This is one of `"start"`, `"finished"`, or `"error"`.

* `ok`:
  * `finished` messages have the JSON boolean value `true`.
  * `error` messages have the JSON boolean value `false`.

* `elapsed`: `finished` and `error` messages have an `elapsed` field whose value
  is the time span since the corresponding start event in milliseconds
  (formatted as a JSON number).


### Sync phase

`sync` events have the following fields:

* remote: The URL on the remote system from which data is being pulled.

### Entity phase

`entity` events surround the syncing for each type of record, i.e. catalogs,
facts, or reports.

* `entity`: The entity being processed. One of `"catalogs"`, `"facts"`,
  `"reports"`, or `"nodes"`. Nodes is used only to sync node deactivation
  status.

* remote: As above

* `transferred`: For the `finished` message, the number of records that were
  transferred (pulled) and enqueued for processing.

* `failed`: For the `finished` message, the number of records that could not be
  transferred.

### Record phase

`record` events surround the transfer of each record, for the `catalogs`,
`facts`, and `reports` entities. They are logged at debug level, unless there is
a problem.

* query: The query issued to the remote PuppetDB server to retrieve this record

* certname: The certname of the node with which this record is associated

* hash (reports only): The hash of the report being transferred

* entity: As above

* remote: As above

### Deactivate phase

`deactivate` events surround the process of issuing a local `deactivate node`
command for a particular node.

* certname: The certname of the node being deactivated

## Metrics

Some additional metrics are provided to help monitor the state of your HA setup.
For basic information on metrics in PuppetDB, see
[the main metrics documentation][metrics].

These metrics are all available via the metrics HTTP endpoint. For example, you
can fetch the data using cURL like this:

```sh
curl http://localhost:8080/metrics/v1/mbeans/puppetlabs.puppetdb.ha:name\=sync-has-worked-once
```

The following metrics are all located in the `puppetlabs.puppetdb.ha` namespace.

* `last-sync-succeeded` (boolean): Did the last sync succeed?
* `sync-has-worked-once`(boolean): Has the sync worked at all since starting this process?
* `sync-has-failed-after-working`(boolean): Has the sync stopped working after
  working previously? This may be useful for ignoring errors that occur when many
  machines in the same cluster are starting at the same time.
* `last-successful-sync-time` (timestamp): The wall-clock time, on the PuppetDB
  server, of when the last successful sync finished.
* `last-failed-sync-time` (timestamp): The wall-clock time, on the PuppetDB
  server, of when the last failed sync finished.
* `seconds-since-last-successful-sync` (integer): The amount of time that
  elapsed since the last time a sync succeeded.
* `seconds-since-last-failed-sync` (integer): The amount of time that elapsed
  since the last time a sync failed.
* `failed-request-counter` (integer): The number of sync-related http requests
  that have failed. Even if syncs are not failing, this may increase when
  individual requests fail and are retried. Unexpected increases in this counter
  should be investigated.

The following metrics expose timing statistics for various phases of the sync.
See the Structured Logging section for a detailed explanation of each phase.

* `sync-duration`
* `catalogs-sync-duration`
* `reports-sync-duration`
* `factsets-sync-duration`
* `nodes-sync-duration`
* `record-transfer-duration`
