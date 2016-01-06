-
title: "PE PuppetDB 3.0 » PuppetDB HA Guide"
layout: default
canonical: "/puppetdb/latest/ha-guide.html
---

[configure]: ./configure.html
[pe-configure]: ./pe-configure.html
[logging]: ./logging.html
[metrics]: ./api/metrics/v1/index.html

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

    - If you are using Amazon RDS, you will need two databases, each in a
      different AZ. Be sure to place your PuppetDB nodes in the same AZ as the
      RDS database it uses.

    - The RDS 'Multi-AZ' feature is not suitable for PuppetDB HA. It is built
      using database-level replication; PuppetDB requires the databases to be
      configured independently and performs synchronization at the application
      level.

PE PuppetDB HA Installation, Configuration and Provisioning
----

#### Prepare the Puppet master

1. Select an agent to become the secondary PuppetDB, which we will call `replica`
for the rest of this doc. The original PuppetDB will be referred to as
`primary`.
2. Add the certname of the `replica` to `/etc/puppetlabs/puppet/autosign.conf`
on the master node.
3. Restart the master to load the `autosign.conf`, e.g. `service pe-puppetserver restart`.

#### Prepare the primary PuppetDB

1. Now from the Console go to `Nodes > Classification > PE PuppetDB > Classes`
and under the class `puppet_enterprise::profile::puppetdb` change the
`whitelisted_certnames` parameter to include the certname of the `replica`. This
will allow the `replica` PuppetDB to talk to the `primary` PuppetDB. Note that
this parameter is a list and *not* a string, e.g. `whitelisted_certnames => [ "replica.puppetdb.vm" ]`.
3. Run `puppet agent -t` on the `primary` PuppetDB node.

#### Install the secondary PuppetDB

1. To install the secondary PuppetDB, have the PE installer handy. To install
only PuppetDB using the installer you'll either need to go through the
*interview* or use an answer file. To walk through the interview, run the
installer with `-A <path to an empty txt file>` as an argument.
2. Now you will need to add the `sync` configuration to the `replica` to let it
know where to talk to the `primary` PuppetDB. Add the following contents to
`/etc/puppetlabs/puppetdb/conf.d/sync.ini`

        [sync]
        server_urls=https://<primary PuppetDB host>:8081
        intervals=2m

3. Restart this `replica` PuppetDB using `service pe-puppetdb restart` and wait
for the initial "sync" with the `primary` PuppetDB to complete by tailing the
log, e.g. `/var/log/puppetlabs/puppetdb/puppetdb.log` of the `replica` PuppetDB.
Once the initial "sync" is complete it is safe to move on to the next step and
configure PE to use the `replica` PuppetDB.

#### Classification of the secondary PuppetDB

1. From the Console select `Nodes > Classification`.
2. Create the Node Group `PE PuppetDB Replica` with `Parent Name` set to `PE
Infrastructure` and `Environment` set to `production`. The Node Groups list
should now include `PE PuppetDB Replica`.
3. Click into the class `PE PuppetDB Replica` from the Node Groups list.
4. Pin the `replica` node to the group, under `certname` and `Pin node`.
5. From the `Classes` tab, add the class `puppet_enterprise::profile::puppetdb`.
6. Add the necessary parameters to the `puppet_enterprise::profile::puppetdb`
class, most likely `database_host` to `replica`'s certname and
`whitelisted_certnames` to contain the `primary` PuppetDB's certname, e.g.
`[ "primary.puppetdb.vm" ]`.
7. Run `puppet agent -t` on the `replica`. If PuppetDB didn't automatically restart,
do so now with `service pe-puppetdb restart`. 

#### Configure PE to use PuppetDB HA

1. From `Nodes > Classification > PE Infrastructure > Classes` change the
`puppetdb_host` parameter to be a list of both the `primary` and `replica`
PuppetDBs, e.g. `[ "primary.puppetdb.vm", "replica.puppetdb.vm"]`.
2. On the `primary` PuppetDB, you will now need to add the `sync` configuration
to the `primary` to let it know where to talk to the `replica` PuppetDB. Add the
following contents to `/etc/puppetlabs/puppetdb/conf.d/sync.ini`

        [sync]
        server_urls=https://<primary PuppetDB host>:8081
        intervals=2m

3. Run `puppet agent -t` on the Master and restart the `primary` PuppetDB with
`service pe-puppetdb restart`.
4. Once these Puppet runs are complete, PuppetDB HA should be operational.
     
Manual Installation and Configuration
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
  `"nodes"`; nodes is used only to sync node deactivation status.

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

## Metrics

Some additional metrics are provided to help monitor the state of your HA setup.
For basic information on metrics in PuppetDB, see
[metrics][the main metrics documentation].

These metrics are all available via the metrics http endpoint; for example, you
can fetch the data using cURL like this:

```sh
curl http://localhost:8080/metrics/v1/mbeans/puppetlabs.puppetdb.ha:name\=sync-has-worked-once
```

The following metrics are all located in the
`puppetlabs.puppetdb.ha` namespace.

* `last-sync-succeeded`: Did the last sync succeed? (boolean)
* `sync-has-worked-once`: Has the sync worked at all since starting this processes?
* `sync-has-failed-after-working`: Has the sync stopped working after working
  once? This may be useful for ignoring errors that occur when many machines in
  the same cluster are starting at the same time.
* `last-successful-sync-time`: The wall-clock time, on the PuppetDB server, of
  when the last successful sync finished.
* `last-failed-sync-time`: The wall-clock time, on the PuppetDB server, of
  when the last failed sync finished.
* `seconds-since-last-successful-sync`: The amount of time that elapsed since
  the last time a sync succeeded.
* `seconds-since-last-failed-sync`: The amount of time that elapsed since
  the last time a sync failed.
* `failed-request-counter`: The number of sync-related http requests that have
  failed. Even if syncs are not failing, this may increase when individual
  requests fail and are retried. Unexpected increases in this counter should be
  investigated.

The following metrics expose timing statistics for various phases of the sync;
see the Structured Logging section for a detailed explanation of each phase.

* `sync-duration`
* `catalogs-sync-duration`
* `reports-sync-duration`
* `factsets-sync-duration`
* `nodes-sync-duration`
* `record-transfer-duration`
