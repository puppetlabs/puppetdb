---
title: "PuppetDB 4.2: Troubleshooting KahaDB corruption"
layout: default
canonical: "/puppetdb/latest/trouble_kahadb_corruption.html"
---

[configure_vardir]: ./configure.html#vardir
[tracker]: https://tickets.puppetlabs.com/browse/PDB

What is KahaDB?
-----

PuppetDB uses ActiveMQ for queuing commands, both those received via the API and sometimes those initiated internally. The queue utilizes a technology built for ActiveMQ called **KahaDB**, which is a file-based persistence database designed specifically for high-performance queuing.

In PuppetDB, KahaDB storage is located in a sub-directory underneath your configured `vardir` (see [the configuration guide][configure_vardir] for more details). This sub-directory is generally `mq/localhost/KahaDB`. For open source PuppetDB, the full path is usually `/opt/puppetlabs/server/data/puppetdb/mq/localhost/KahaDB`.

Why does corruption occur?
-----

In some cases, KahaDB's storage might become corrupt or simply unreadable
due to the version of PuppetDB that you've launched. There are a number
of possible causes, including:

* Your disk may fill up, so writes are not finalized within the journal or database index.

* There might be a bug in the KahaDB code that the developers haven't catered for.

* You might downgrade PuppetDB to a version that uses an incompatible
  ActiveMQ without clearing the queue directory.

How do I recover?
-----

During corruption, the simplest way to recover is to simply move the mq directory
(which contains the KahaDB and scheduler directories) out of the way and
restart PuppetDB:

    $ service puppetdb stop
    $ cd /opt/puppetlabs/server/data/puppetdb
    $ mv mq mq.old
    $ service puppetdb start

(**Note:** it is very important to preserve the old mq directory. If your issue
turns out to be something our developers haven't seen before, we'll need that
directory to replicate the problem.)

In most cases this will solve the problem, though in the process you might lose
any queued, unprocessed data (data that had not reached PostgreSQL yet).
Re-running Puppet on your nodes should normally resubmit the lost commands.

How do I bring my corruption issue to the attention of developers?
-----

Whenever possible, we want to hear about your corruption so that we can improve our approach to these problems. If you are affected, please search our [Bug Tracker][tracker] for the term `KahaDB` to see if your problem is already known. If it is, please add a comment with your PuppetDB version.

If your problem is not already logged on the Bug Tracker, please file a new ticket that includes your `puppetdb.log` file (or at least the pertinent exception) including the version of PuppetDB you are using and the potential cause of the corruption, if known. In all cases, please preserve any backups of the `mq` directory in its original corrupted state, which may be helpful to our developers.
