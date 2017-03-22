---
title: "PuppetDB 4.2 » FAQ"
layout: default
subtitle: "Frequently asked questions"
---

[trouble_kahadb]: ./trouble_kahadb_corruption.html
[maintaining_tuning]: ./maintain_and_tune.html
[connect_puppet_apply]: ./connect_puppet_apply.html
[puppetdb3]: /puppetdb/3.2/migrate.html

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

The officially supported versions are OpenJDK and Oracle JDK 1.7 on
Debian-derived distros and 1.8 on RHEL-derived distros. Other versions may work,
and issues will be addressed on a best-effort basis, but support is not guaranteed.

## Which databases are supported?

PostgreSQL is the recommended database for production use. 

As with our choice of language, we prototyped several
databases before settling on PostgreSQL. These included Neo4j, Riak, and MySQL
with ActiveRecord in Ruby. We have no plans to support any other databases,
including MySQL, which lacks important features such as array columns and
recursive queries.

## I may have a corrupt KahaDB store. What does this mean, what causes it, and how can I recover?

If PuppetDB throws an exception when the application starts or while receiving
a command, it may be due to KahaDB corruption. The exception generally includes some
mention of the KahaDB libraries (org.apache.activemq.store.kahadb). For example:

    java.io.EOFException
        at java.io.RandomAccessFile.readInt(RandomAccessFile.java:776)
        at org.apache.activemq.store.kahadb.disk.journal.DataFileAccessor.readRecord(DataFileAccessor.java:81)

You should consult the [troubleshooting guide for KahaDB][trouble_kahadb] for
details on how to rememdy this.

## The PuppetDB daemon shuts down with a "Cannot assign requested address" error. What does this mean, and how do I fix it?

~~~
FAILED org.eclipse.jetty.server.Server@6b2c636d: java.net.BindException: Cannot assign requested address
java.net.BindException: Cannot assign requested address
~~~

PuppetDB will error with this message if the IP address associated with the
ssl-host parameter in the jetty.ini isn't linked to a known interface or
resolvable.

## Why is the load so high on the database server?

There are many possible reasons for a high load on the database server,
including the total number of nodes managed by Puppet, the frequency of the
agent runs, and the number of changes to the nodes on each run. One possible
cause of execessive load on the database server is a low catalog duplication
rate. See the [PuppetDB dashboard][maintaining_tuning] to find this rate for
your PuppetDB instance. If this rate is significantly lower than 90%, see
[Why is my catalog duplication rate so low?](#why-is-my-catalog-duplication-rate-so-low).

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
