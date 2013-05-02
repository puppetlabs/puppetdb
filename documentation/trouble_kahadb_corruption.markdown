---
title: "PuppetDB 1.3 » Troubleshooting » KahaDB Corruption"
layout: default
canonical: "/puppetdb/latest/trouble_kahadb_corruption.html"
---

[configure_vardir]: ./configure.html#vardir
[redmine]: http://projects.puppetlabs.com/projects/puppetdb

What is KahaDB?
-----

Internally PuppetDB utilises ActiveMQ for queuing commands received via the API and sometimes initiated internally. The queue today utilises a technology built for ActiveMQ called 'KahaDB' which is a file based persistence database designed specifically for high performance queuing.  

The KahaDB storage for PuppetDB is located in a sub-directory underneath your configured `vardir` (see [Configuration][configure_vardir] for more details). This sub-directory is generally, `mq/localhost/KahaDB`. For OSS PuppetDB the full path is usually `/var/lib/puppetdb/mq/localhost/KahaDB`.

Why does corruption occur?
-----

In some cases this database may corrupt. Lots of things may cause this:

* Your disk may fill up, so writes are not finalised within the journal or database index.
* There might be a bug in the KahaDB code that the developers haven't catered for.

How do I recover?
-----

During corruption, the simplest way to recover is to simply move the KahaDB directory out of the way and restart PuppetDB:

    $ service puppetdb stop
    $ cd /var/lib/puppetdb/mq/localhost
    $ mv KahaDB KahaDB.old
    $ service puppetdb start

(*Note:* it is very important for us that you preserve the old KahaDB directory. If the problem turns out to be something our Engineers haven't seen before we'll need that directory to replicate the problem, so make sure you preserve it.)

In most cases this is enough, however this means that any data that was not processed may be lost. This is usually only transient queue data however, and is not the persisted data that is stored in your PostgreSQL or HSQLDB database, so in most cases it is not a major concern. For most cases re-running puppet on your nodes will resubmit these lost commands for processing.

If these is going to be too destructive, then there is a few things you can do. But first of all, backup your KahaDB directory before doing anything so you can revert it after each attempt at the techniques listed below:

* You can try clearing your `db.data` file and recreating it. The `db.data` file represents your index, and clearing it may force it to be recreated from the logs.
* You can try clearing your `db-*.log` files. These files contain the journal and while KahaDB is usually good at finding pin-point corruption and ignoring these today (in fact much better since PuppetDB 1.1.0) there are still edge cases.  Clearing them may let you skip over these bad blocks. It might be that only 1 of these files are corrupted, and the remainder are good so you could attempt clearing one at a time (newest first) to find the culprit.

How do I bring my corruption to the attention of developers?
-----

In almost all cases though we want to hear about your corruption so we can improve the ways we deal with these problems. We would appreciate if you have these issues to look at our [Bug Tracker][redmine] for the term `kahadb` to see if you're problem is already known, and adding a comment if you see it yourself, including the version of PuppetDB you are using.

If the problem is unknown or new, make sure you log a new ticket including your `puppetdb.log` file, or at least the pertinent exception including the version of PuppetDB you are using and the potential cause of the corruption if you are aware of it. In all cases, make sure you preserve any backups of the `KahaDB` directory in its original corrupted state, this may be helpful to our Software Engineers to replicate the problem later.
