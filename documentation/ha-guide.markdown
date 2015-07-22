-
title: "PE PuppetDB 3.0 » PuppetDB HA Guide"
layout: default
canonical: "/puppetdb/latest/ha-guide.html
---

[configure]: ./configure.html
[pe-configure]: ./pe-configure.html
[logging]: ./logging.html

Configuring PuppetDB for High Availability
-----

This document describes how to configure Puppet with a high-availability
PuppetDB system that can withstand network partitions or system failure. 

HA Overview
-----

PuppetDB is an eventually consistent system. Even in a single-node setup, write
commands are put in a queue until they can be processed. It thus may take some
time before a submitted command is applied and its effect is seen in queries.

The replication system reflects and extends this reality. It is pull-based; each
server polls a remote server a configurable interval. It fetches from each of
them a summary a summary of the records it holds. It then compares this with its
local state, applies precedence rules, and downloads any missing or changed
records.

PuppetDB replication is in principle a multi-master system; you can issue
commands to any servers in the cluster and they will sync with each other
without conflicts. But, in order to ensure a minimal amount of confusion when
using exported resources, we recommend that only one system be conventionally
treated as primary. ([See below](#exportedResources) for further discussion)

Network Topologies
----

- *Only 2-node topolgies are currently supported.* This restriction will be
  eased in the future as we verify more topologies. But until then, the only
  supported configuration is two PuppetDB instances, each pulling from the
  other.

- Multi-datacenter replication is not currently supported. 

- Each PuppetDB node should be configured to use a separate PostgreSQL instance.

    - As with standalone PuppetDB, we recommend that you run PostgreSQL on a
      separate machine from your regular PuppetDB instance.

- When deploying to AWS or a similar cloud environment, each PuppetDB-PostgreSQL
  node pair should be deployed in a separate availability zone.

    - If you are using Amazon RDS, be aware that each region hosts RDS servers
      in only a single availability zone. You can deploy PuppetDB on an EC2
      instance within that region, but you'll have to run your own PostgreSQL
      instance along with a replica PuppetDB in a different AZ.

    - The RDS 'Multi-AZ' feature is not suitable for PuppetDB HA. It is built
      using database-level replication; PuppetDB requires the databases to be
      configured independently and performs synchronization at the application
      level.

    
Configuration
----

1. Provision two PuppetDB nodes. Designate one of these as your _primary_ PuppetDB,
   and the other as the _replica_. In this guide, we will give them the
   hostnames `primary-puppetdb.mycorp.net` and `replica-puppetdb.mycorp.net`.

2. Configure each PuppetDB to pull from the other. This guide will use
   HOCON-style configuration; see the
   [pe-configure][PE Configuration Documentation] for further details.

  a. Configure main-puppetdb to pull from replica-puppetdb by placing this in its
     config file:

         sync: {
           remotes: [{server_url: "https://replica-puppetdb.mycorp.net:8081",
                      interval: 5m}]
         }

  b. Configure replica-puppetdb to pull from primary-puppetdb:

         sync: {
           remotes: [{server_url: "https://primary-puppetdb.mycorp.net:8081",
                      interval: 5m}]
         }

3. Configure the Puppet master to point to both of your PuppetDB instances. In
   your puppetdb.conf, use this as your server configuration (in the 'main' section):

        server_urls: ["https://primary-puppetdb.mycorp.net:8081", "https://replica-puppetdb.mycorp.net:8081"]

   It is important that your primary PuppetDB appears first in this list, so it
   will always be tried first when submitting new data from the Puppet master.

4. Restart your PuppetDBs and your Puppet master and enjoy!

FAQ
-----

Q: *Why not just use PostgreSQL streaming replication?*

A: The principle goal of PuppetDB's HA solution is to ensure that Puppet runs
never fail, even in the face of losing machines and intermittent network
connections. These precludes any possibility of coordinating writes among the
members of the cluster; that is, this is a system that cares principally about
availability and second about consistency.

Relational database replication systems tend to emphasize consistency. Those
that are availability-focused must concern themselves with conflict resolution.
For PuppetDB in particular, conflict resolution at the database level would be
very complex and potentially intractable. Conversely, at the application level
we can view the entities in very course terms (e.g. factset vs. many fact rows).
This allows us to resolve any conflicts very predictably.

Q: <a name="exportedResources"></a>*What's the deal with exported resources?*

A: Exported resources are a great feature, but you need to be careful when using
them. In particular: because PuppetDB is *eventually* consistent, changes to
exported resources will *eventually* be visible on other nodes. (not
immediately) This is true in any PuppetDB installation, but there is an added
subtlety which you should be aware of when using an HA configuration:

In failover scenarios, exported resources may appear to go back in time. This
can happen if commands have been written to your primary PuppetDB but haven't yet
been copied to the replica. If there is the primary PuppetDB becomes unreachable
in this state, exported resource queries will be redirected to the replica,
which does not yet have the latest data.

Q: *What durability guarantees does PuppetDB HA offer?*

A: In the current beta, it is possible to lose data if the primary PuppetDB's
database or command queue are completely destroyed before the data can be
synchronized with the replica. This will be fixed in first stable version by
retaining a log of submitted commands on the Puppet master and using it to
recover from data loss cases.

Operations
-----


Should :transferred and :failed be documented?


PuppetDB provides several facilities to help you keep your HA cluster running.

## Structured Logging

HA-related events are written to a log named ":sync"; you can configure the
handling of these events to fit your requirements.

If you have configured structured logging as described in the
[logging][Logging Configuration Guide], you will see additional attributes on each JSON log message.

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

* `entity`: The entity being processed. One of `"catalogs"`, `"facts"`, `"reports"`, or
  `"nodes"`. (nodes is used only to sync node deactivation status)

* remote: As above

* `transferred`: For the `finished` message, the number of records that were
  transferred (pulled) and enqueued for processing.

* `failed`: For the `finished` message, the number of records that could not be
  transferred.

### Record phase

`record` events surround the transfer of each record, for the `catalogs`,
`facts`, and `reports` entities. They are logged at debug level, unless there is
a problem.

* query: The query issued to the remote PuppetDB server to retrieve this record.

* certname: The certname of the node with which this record is associated

* hash: The hash of the report being transferred, for reports only.

* entity: As above

* remote: As above

### Deactivate phase

`deactivate` events surround the process of issuing a local `deactivate node`
command for a particular node.

* certname: The certname of the node being deactivated
