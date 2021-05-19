---
title: "Scaling recommendations"
layout: default
canonical: "/puppetdb/latest/scaling_recommendations.html"
---
# Scaling recommendations

[configure_heap]: ./configure.html#configuring-the-java-heap-size
[dashboard]: ./maintain_and_tune.html#monitor-the-performance-dashboard
[heap]: ./maintain_and_tune.html#tune-the-max-heap-size
[threads]: ./maintain_and_tune.html#tune-the-number-of-threads
[postgres]: ./configure.html#using-postgresql
[pg_ha]: http://www.postgresql.org/docs/current/interactive/high-availability.html
[pg_replication]: http://wiki.postgresql.org/wiki/Replication,_Clustering,_and_Connection_Pooling
[ram]: #bottleneck-java-heap-size
[runinterval]: {{puppet}}/configuration.html#runinterval

PuppetDB will be a critical component of your Puppet deployment, as agent nodes will be unable to request catalogs if it goes down. Therefore, you should make sure it can handle your site's load and is resilient against failures.

When scaling any service, there are several possible performance and reliability bottlenecks. These can be dealt with in turn as they become problems.


## Bottleneck: Database performance

### PostgreSQL speed and availability

PuppetDB will be limited by the performance of your PostgreSQL server.
You can increase performance by making sure your database server has an
extremely fast disk, plenty of RAM, a fast processor, and a fast
network connection to your PuppetDB server. You may also need to look
into database clustering and load balancing.

It's also possible that the default PostgreSQL configuration on your
system will be very conservative. If so, the
[PgTune](http://pgfoundry.org/projects/pgtune/) tool can suggest
settings that may be more appropriate for the relevant host.

Database administration is beyond the scope of this guide, but the
following links may be helpful:

* ["High availability, load balancing, and replication"][pg_ha], from the PostgreSQL manual
* ["Replication, clustering, and connection pooling"][pg_replication], from the PostgreSQL wiki

## Bottleneck: Java heap size

PuppetDB is limited by the amount of memory available to it, which is [set in the init script's config file][configure_heap]. If PuppetDB runs out of memory, it will start logging `OutOfMemoryError` exceptions and delaying command processing. Unlike many of the bottlenecks listed here, this one is fairly binary: PuppetDB either has enough memory to function under its load, or it doesn't. The exact amount needed will depend on the number of nodes, the similarity of the nodes, the complexity of each node's catalog, and how often the nodes check in.

### Initial memory recommendations

Use one of the following rules of thumb to choose an initial heap size; afterwards, [watch the performance dashboard][dashboard] and [adjust the heap if necessary][heap].

* If you are using PostgreSQL, allocate 128 MB of memory as a base, plus 1 MB for each Puppet node in your infrastructure.
* If you are using the embedded database, allocate at least 1 GB of heap.

## Bottleneck: Node check-in interval

The more frequently your Puppet nodes check in, the heavier the load on your PuppetDB server.

You can reduce the need for higher performance by changing the [`runinterval`][runinterval] setting in every Puppet node's puppet.conf file. (Or, if running Puppet agent from cron, by changing the frequency of the cron task.)

The frequency with which nodes should check in will depend on your site's policies and expectations --- this is as much a cultural decision as it is a technical one. A possible compromise is to use a wider default check-in interval, but implement MCollective's `puppetd` plugin to trigger immediate runs when needed.

## Bottleneck: CPU cores and number of worker threads

PuppetDB can take advantage of multiple CPU cores to handle the commands in its queue. Each core can run a worker thread. By default, PuppetDB will use half of the cores in its machine.

You can increase performance by running PuppetDB on a machine with many CPU cores and then [tuning the number of worker threads][threads]:

* More threads will allow PuppetDB to keep up with more incoming commands per minute. Watch the queue depth in the performance dashboard to see whether you need more threads.
* Too many worker threads can potentially starve the message queue and web server of resources, which will prevent incoming commands from entering the queue in a timely fashion. Watch your server's CPU usage to see whether the cores are saturated.

## Bottleneck: Single point of failure

Although a single PuppetDB and PostgreSQL server probably _can_ handle all of the load at the site, you may want to run multiple servers for the sake of resilience and redundancy. To configure high-availability PuppetDB, you should:

* Run multiple instances of PuppetDB on multiple servers, and use a reverse proxy or load balancer to distribute traffic between them.
* Configure multiple PostgreSQL servers for high availability or clustering. More information is available at [the PostgreSQL manual][pg_ha] and [the PostgreSQL wiki][pg_replication].
* Configure every PuppetDB instance to use the same PostgreSQL database. (In the case of clustered PostgreSQL servers, they may be speaking to different machines, but conceptually they should all be writing to one database.)


## Bottleneck: SSL performance

PuppetDB uses its own embedded SSL processing, which is usually not a performance problem. However, truly large deployments will be able to squeeze out more performance by terminating SSL with Apache or NGINX instead. If you are using multiple PuppetDB servers behind a reverse proxy, we recommend terminating SSL at the proxy server.

Instructions for configuring external SSL termination are currently beyond the scope of this guide. However, we expect that if your site is big enough for this to be necessary, you have probably done it with several other services before.
