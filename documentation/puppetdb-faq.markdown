---
title: "PuppetDB: FAQ"
layout: default
subtitle: "Frequently asked questions"
---

[maintaining_tuning]: ./maintain_and_tune.html
[connect_puppet_apply]: ./connect_puppet_apply.html
[support_guide]: ./pdb_support_guide.markdown
[puppetdb3]: /puppetdb/3.2/migrate.html
[threads]: ./configure.html#threads
[concurrent-writes]: ./configure.html#concurrent-writes
[mq metrics]: ./api/metrics/v1/mbeans.html#message-queue-metrics
[java heap]: ./configure.html#configuring-the-java-heap-size

## Can I migrate my data from ActiveRecord storeconfigs?

Yes, but you must use PuppetDB 3.x to do so. Please consult the
[PuppetDB 3.x documentiation][puppetdb3] for more details.

## Can I migrate from an HSQL PuppetDB to PostgreSQL PuppetDB instance?

Yes, but you must use PuppetDB 3.x to do so. Please consult the
[Migrating Data][puppetdb3] for more information.

## The PuppetDB dashboard gives me a weird SSL error when I visit it. What gives?

There are two common error cases with the dashboard:

* You're trying to talk over plain text (8080) and PuppetDB's not listening.

By default, PuppetDB only listens for plain text connections on localhost, for
security reasons. In order to talk to it this way, you'll need to either
forward the plain text port or change the interface PuppetDB binds on to one
that is accessible from the outside world. In the latter case, you'll want to
use some other means to secure PuppetDB (for instance, by restricting which
hosts are allowed to talk to PuppetDB through a firewall).

* You're trying to talk over SSL and nobody trusts anybody else.

Because PuppetDB uses the certificate authority (CA) of your Puppet
infrastructure, and a certificate signed by it, PuppetDB doesn't trust your
browser, and your browser doesn't trust PuppetDB. In this case, you'll need to
give your browser a certificate signed by your Puppet CA. Support for client
certificates is varied between browsers, so it's preferred to connect over
plain text, as outlined above.

## Does PuppetDB support Puppet apply?

Yes. However, the setup is quite different from the normal master-based setup, so
[consult the documentation][connect_puppet_apply] for more details.

## Why is PuppetDB written in Java?

Actually, PuppetDB isn't written in Java at all! It's written in a language
called Clojure, which is a dialect of Lisp that runs on the Java Virtual
Machine (JVM). Several other languages were prototyped, including Ruby and JRuby, but
they lacked the necessary performance. We chose to use a JVM language because
of its excellent libraries and high performance. Of the available JVM
languages, we used Clojure because of its expressiveness, performance, and
our team's previous experience with the language.

## Which versions of Java are supported?

JDK 8 is officially supported, and JDK 10 is expected to work.  Other
versions may work, and issues will be addressed on a best-effort
basis, but support is not guaranteed.

## Which databases are supported?

PostgreSQL is the recommended database for production use.

As with our choice of language, we prototyped several
databases before settling on PostgreSQL. These included Neo4j, Riak, and MySQL
with ActiveRecord in Ruby. We have no plans to support any other databases,
including MySQL, which lacks important features such as array columns and
recursive queries.

## Why does it error when `pg_trgm` is not installed?

The expected behavior is a warning when the `pg_trgm` extension is not installed.
If you are seeing a message that asks you to run `CREATE EXTENSION pg_trgm;`
then it's not erroring, but we do suggest you install the `pg_trgm` extension.
The error can be seen in your PostgreSQL log and the PuppetDB log and looks like the output below.

PostgreSQL:

      < 2016-08-10 14:03:04.523 PDT >ERROR: could not access file "$libdir/pg_trgm": No such file or directory
      < 2016-08-10 14:03:04.523 PDT >STATEMENT: CREATE INDEX fact_values_string_trgm ON fact_values USING gin (value_string gin_trgm_ops)

PuppetDB:

      2018-08-01 18:32:31,433 INFO  [async-dispatch-2] [p.p.s.migrate] Creating additional index `fact_paths_path_trgm`
      2018-08-01 18:32:31,513 ERROR [async-dispatch-2] [p.t.internal] Error during service start!!!
      java.sql.BatchUpdateException: Batch entry 0 CREATE INDEX fact_paths_path_trgm ON fact_paths USING gist (path gist_trgm_ops) was aborted.

This error occurs when the database believes that `pg_trgm` has been installed, but for some
reason the extension has been uninstalled or removed. Ensure the PostgreSQL extension `pg_trgm` has been installed.
Depending on your operating system, you may be able to install this extension by installing the `postgresql-contrib` package.

## The PuppetDB daemon shuts down with a "Cannot assign requested address" error. What does this mean, and how do I fix it?

~~~
FAILED org.eclipse.jetty.server.Server@6b2c636d: java.net.BindException: Cannot assign requested address
java.net.BindException: Cannot assign requested address
~~~

PuppetDB will error with this message if the IP address associated with the
ssl-host parameter in the jetty.ini isn't linked to a known interface or
resolvable.

## Why is PuppetDB using so much CPU?

There are numerous possible contributing factors to high CPU usage by PuppetDB,
both on the application server and (if different) the database. Examples
include the total number of nodes managed by Puppet, the frequency of the agent
runs, and the number of changes to the nodes on each run. For more information
on possible causes and ways to mitigate them, refer to the [support and
troubleshooting guide][support_guide].

## My Puppet master is running slower since I enabled PuppetDB. How can I profile it?

Puppet 3.x introduced a new profiling capability that we leveraged in the
puppetdb-termini client code. By simply adding `profile=true` to your
`puppet.conf`, you can enable detailed profiling of all aspects of Puppet,
including puppetdb-termini. For this to work, you must enable debugging on your
master instance as well.

**Note:** We encourage all users to use common sense when working with profiling
mechanisms. Using these tools will add more load, which can increase speed
problems in a limited capacity environment. Enabling profiling in production
environments should only be done with care and for a very short period of time.

To enable easy searching, all PuppetDB profiling events are prefixed with
`PuppetDB:`. This information is also helpful to our developers, so feel free to
include these details when reporting issues with PuppetDB.

## How can I improve command processing performance?

When PuppetDB is running in a "steady state", it should have a very
low [queue depth][mq metrics] (ideally 0). Something like a database
outage can cause a temporary spike in queue depth. Having a queue
depth without an outage or other significant event likely means that
PuppetDB can't keep up with the work that is being enqueued. This is a
good indication that some performance tuning needs to take
place. There are several areas to consider when performance
tuning. PuppetDB is sensitive to PostgreSQL performance issues, so
usually that is a good place to start. Assuming that the PostgreSQL
instance isn't under a heavy load, the focus can shift to tuning
PuppetDB itself.

The [threads][threads] setting indicates how many commands can be
processed concurrently. If PuppetDB is consuming too many resources on
a shared system, this number can be reduced. For servers that are
dedicated PuppetDB instances, setting this value to the number of
logical cores could significantly improve command
throughput. Increasing the number of threads should also be paired
with increasing the [amount of memory allocated to PuppetDB][java heap].

The [concurrent-writes][concurrent-writes] setting indicates how many
threads can write to the disk at one time. Faster enqueuing results in
faster Puppet runs, because the PuppetDB terminus enqueues the message as
part of the Puppet run. The impact of this setting is heavily related
to disk performance on the system. On a system with an SSD, this
setting will have very little impact on performance or load on the
system. On a system with a spinning disk, this setting can heavily
impact load average and command throughput. Having this setting higher
than the default (i.e. 16 or 32) could result in faster enqueuing, but
will also result in a significant spike in load average as the kernel
will have I/O write requests "backed up". Changing this setting to
lower than the default should reduce the load on the system but will
reduce the throughput on the PuppetDB instance. That could potentially
increase the time it takes to enqueue a command and thus slow the
puppet runs.
