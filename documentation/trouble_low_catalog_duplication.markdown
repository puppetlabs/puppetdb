---
title: "PuppetDB 3.0 » Troubleshooting » Low Catalog Duplication"
layout: default
canonical: "/puppetdb/latest/trouble_low_catalog_duplication"
---

[maintaining_tuning]: ./maintain_and_tune.html
[configure_vardir]: ./configure.html#vardir
[global_config]: ./configure.html#global-settings
[configure_vardir]: ./configure.html#catalog-hash-conflict-debugging
[configure_catalog_debugging]: ./configure.html#catalog-hash-conflict-debugging

What is Catalog Duplication?
-----

Every puppet agent run on a host results in a new catalog sent from
the puppet master to PuppetDB. Although catalogs can be very large,
typically there are 1000 or more resources and their relationships in
each of the catalogs. Storing each of these resources and
relationships is a fairly I/O-intensive operation on the database used
by PuppetDB.

Puppet agent runs happen frequently; typically every 60
minutes. Most of these runs will not result in a catalog with
different resources or different relationships. Typically, only a
manually initiated change to Puppet content, such as modification of a
manifest or a Puppet extension, will update a node's catalog.

PuppetDB stores catalogs with this use case in mind. PuppetDB will SHA1 hash
the contents of a catalog and will compare that hash with the hash of
the previously received catalog for that host. Only when these hashes
don't match will PuppetDB incur the extra cost/overhead of storing the
new catalog (with all of its resources and edges).

The _catalog duplication rate_ is a measure of how often PuppetDB is able
to _not_ store a whole catalog. It can be found on the PuppetDB dashboard as a
percentage. (More information on the dashboard can be found in the
[Maintaining and Tuning guide][maintaining_tuning].) Typically, this
catalog duplication rate should be over 90%. If this percentage is
low, there will be a significantly higher I/O load on the database.
This load can lead to slowness and resource contention for the
database and PuppetDB itself.

What Causes Catalogs to Hash Differently?
-----

Anything that changes, adds or removes a resource or a relationship
will cause the catalog for that host to hash differently. Most
commonly, this is caused by an admin initiated change to a host (i.e.
adding a new package to the configuration of a web server).

Prior to
PuppetDB 1.6, this could also be caused by a subtle reordering of
resources or properties. One example of that scenario is in a two
puppet master setup, one puppet master would send its resources in a
different order than the other puppet master. This would cause the
catalogs to hash differently, even though their content was the same.
This issue has been fixed in PuppetDB 1.6.

How Can I Detect the Cause of Catalogs Hashing Differently?
-----

Unfortunately, diagnosing why a catalog is hashing differently can be
difficult. For starters, the hashes look something like
`b4199f8703c5dc208054a62203db132c3d12581c` and by design, a small
change in the catalog results in a completely different hash.

To help troubleshoot these scenarios, PuppetDB includes a catalog hash
debugging feature. To turn this on, set
[catalog-hash-conflict-debugging][configure_catalog_debugging] to true
by adding the following line to [the global PuppetDB
config][global_config] and restart PuppetDB:

    [global]
    vardir = /var/lib/puppetdb
    ...
    catalog-hash-conflict-debugging = true

This will create a new directory `debug/catalog-hashes` under the
[`vardir` directory][configure_vardir]. When this config option is
specified, each time the catalog for a given host doesn't match the
catalog previously stored for that host, it will output that catalog.
There will be five files written for each catalog hash that doesn't
match. All of the file names are of the format:

    <hostname>_<UUID>_<name of content>.<ext>

**The files that will be most useful** in detecting the problem are
`*old-catalog.json` and `*new-catalog.json`.
These two files contain a pretty-printed version of the catalog in
JSON format. Diffing these two files is probably the easiest way to
detect differences in the two catalogs.

There is also a metadata file
with the suffix `catalog-metadata.json` that includes the original
hash value, the new hash value and the full paths to all the files
outputted for debugging. The other two debugging files are more useful
for PuppetDB developers and are just the old/new catalogs in an EDN
format.

Usage Example
-----

Below is an example of a catalog debugging session, with steps to
enable debugging, update the catalog and interpret the results. The
goal of this feature and of the example, is to isolate what is
changing from one agent run to another for a given node. This
information will help pinpoint the cause of the low catalog
duplication.

First start with a basic site.pp:

    file { '/tmp/foo' :
      ensure  => present,
      content => "foo"
    }

Run the puppet agent on the node you want to test with. This node
will be referred to as _sample.com_. Running the agent will cause the catalog,
including the _/tmp/foo_ file resource, to be stored in PuppetDB.
Next, to enable catalog hash debugging, add the following line to the
global PuppetDB config:

    catalog-hash-conflict-debugging=true

The typical location of the global config is
`/etc/puppetdb/conf.d/config.ini`. Restart PuppetDB to pickup the
config change:

    sudo /etc/init.d/puppetdb restart

With debugging now enabled, change the manifest in your site.pp so
that _/tmp/foo_ has different content:

    file { '/tmp/foo' :
      ensure  => present,
      content => "bar"
    }

Rerun the puppet agent. There should now be debugging files in the
PuppetDB vardir. By default, this is in
`/var/lib/puppetdb/debug/catalog-hashes`. If the _debug_ directory is
not there, vardir might be in a different locaction on your system.
Look for the vardir property in the global config to find its
location.

Using a command like below, you should see 5 files:

    # ls -1 el6-64.vm*
    sample.com_0dabed38-b999-41a8-b6a3-254915ebcdd7_old-catalog.edn
    sample.com_0dabed38-b999-41a8-b6a3-254915ebcdd7_old-catalog.json
    sample.com_0dabed38-b999-41a8-b6a3-254915ebcdd7_catalog-metadata.json
    sample.com_0dabed38-b999-41a8-b6a3-254915ebcdd7_new-catalog.json
    sample.com_0dabed38-b999-41a8-b6a3-254915ebcdd7_new-catalog.edn

To examine the differences, diff the old and new JSON files:

    # diff -u sample.com_0dabed38-b999-41a8-b6a3-254915ebcdd7_old-catalog.json sample.com_0dabed38-b999-41a8-b6a3-254915ebcdd7_new-catalog.json
    @@ -52,10 +52,10 @@
         "file" : "/path/to/site.pp",
         "line" : 8,
         "parameters" : {
    -      "content" : "foo",
    +      "content" : "bar",
           "ensure" : "present"
         },
         "title" : "/tmp/catalog-test.ox78bt/foo",

In this example, the change from the first run to the second run was
pretty trivial, but following a similar set of steps should also
reveal more significant differences, such as volatile resources that are
changing with every Puppet run.
