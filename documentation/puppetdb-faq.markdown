---
layout: default
title: "PuppetDB 1.3 FAQ"
subtitle: "Frequently Asked Questions"
canonical: "/puppetdb/latest/puppetdb-faq.html"
---

[trouble_kahadb]: ./trouble_kahadb_corruption.html
[migrating]: ./migrate.html

## Can I migrate my data from ActiveRecord storeconfigs or from an existing PuppetDB to a new instance?

Yes.  At this time, you can only migrate exported resources from ActiveRecord, and
you can migrate catalogs from an existing PuppetDB.  For more information, see
[Migrating Data][migrating] for more information.

## PuppetDB is complaining about a truststore or keystore file. What do I do?

There are several related causes for this, but it boils down to PuppetDB being
unable to read your truststore.jks or keystore.jks file. The former file
contains the certificate for your certificate authority, and is what PuppetDB
uses to authenticate clients. The latter contains the key and certificate that
PuppetDB uses to identify itself to clients.

The short answer: you can often fix these problems by reinitializing the keystore
and truststore, by running `/usr/sbin/puppetdb-ssl-setup`. Note that this script
must be run *after* a certificate is generated for the puppet agent (that is:
after the agent has run once and had its certificate request signed). A common
problem is installing PuppetDB before the Puppet agent has run, and this script
will solve that problem, and many others.

The long answer: if the `puppetdb-ssl-setup script` doesn't solve your problem
or if you're curious what's going on under the covers, you can manage this
configuration by hand.  The locations of the truststore and keystore files are set
with the `keystore` and `truststore` options in the config file. There should
also be settings for `key-password` and `trust-password`. Make sure the
keystore.jks and truststore.jks files are where the config says they should be,
and that they're readable by the user PuppetDB runs as (puppetdb for an open
source installation, pe-puppetdb for a Puppet Enterprise installation).
Additionally, you can verify that the password is correct using
`keytool -keystore /path/to/keystore.jks` and and entering the `key-password`.
Similarly, you can use `keytool -keystore /path/to/truststore.jks` to verify the
truststore.

## The PuppetDB dashboard gives me a weird SSL error when I visit it. What gives?

There are two common error cases with the dashboard:

* You're trying to talk over plaintext (8080) and PuppetDB's not listening

By default, PuppetDB only listens for plaintext connections on localhost, for
security reasons. In order to talk to it this way, you'll need to either
forward the plaintext port or change the interface PuppetDB binds on to one
that is accessible from the outside world. In the latter case, you'll want to
use some other means to secure PuppetDB (for instance, by restricting which
hosts are allowed to talk to PuppetDB through a firewall).

* You're trying to talk over SSL and nobody trusts anybody else

Because PuppetDB uses the certificate authority of your Puppet
infrastructure, and a certificate signed by it, PuppetDB doesn't trust your
browser, and your browser doesn't trust PuppetDB. In this case, you'll need to
give your browser a certificate signed by your Puppet CA. Support for client
certificates is varied between browsers, so it's preferred to connect over
plaintext, as outlined above.

## Does PuppetDB support Puppet apply?

Partially. Use with Puppet apply requires some special configuration, and due
to limitations in Puppet, inventory service functionality isn't fully
supported. Catalog storage and collection queries are completely functional,
though. You can find information about configuring Puppet apply to work with
PuppetDB in the installation guide for your version of PuppetDB.

Either of these issues can also be solved through clever and judicious use of
proxies, although the details of that are left as an exercise to the reader.

## Why is PuppetDB written in Java?

Actually, PuppetDB isn't written in Java at all! It's written in a language
called Clojure, which is a dialect of Lisp that runs on the Java Virtual
Machine. Several other languages were prototyped, including Ruby and JRuby, but
they lacked the necessary performance.  We chose to use a JVM language because
of its excellent libraries and high performance. Of the available JVM
languages, we used Clojure because of its expressiveness, performance, and
previous experience with the language on our team.

## Which versions of Java are supported?

The officially supported versions are OpenJDK and Oracle JDK, versions 1.6 and
1.7. Other versions may work and issues will be addressed on a best effort
basis, but support is not guaranteed.

## Which databases are supported?

PostgreSQL is the recommended database for production use. PuppetDB also ships
with an embedded HyperSQL database which is suitable for very small or proof of
concept deployments. As with our choice of language, we prototyped several
databases before settling on PostgreSQL. These included Neo4j, Riak, and MySQL
with ActiveRecord in Ruby. We have no plans to support any other databases,
including MySQL, which lacks important features such as array columns and
recursive queries.

## I may have a corrupt KahaDB store. What does this mean, what causes it and how can I recover?

If PuppetDB throws an exception while the application starts or while receiving
a command it may be due to KahaDB corruption. The exception generally has some
mention of the KahaDB libraries (org.apache.activemq.store.kahadb), for example:

    java.io.EOFException
        at java.io.RandomAccessFile.readInt(RandomAccessFile.java:776)
        at org.apache.activemq.store.kahadb.disk.journal.DataFileAccessor.readRecord(DataFileAccessor.java:81)

You should consult the [Troubleshooting guide for Kahadb][trouble_kahadb] for
details on how to rememdy this.
