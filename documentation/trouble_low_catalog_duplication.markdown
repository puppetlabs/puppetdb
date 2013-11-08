---
title: "PuppetDB 1.6 » Troubleshooting » Low Catalog Duplication"
layout: default
canonical: "/puppetdb/latest/trouble_low_catalog_duplication"
---

[maintaining_tuning]: ./maintain_and_tune.html
[configure_vardir]: ./configure.html#vardir
[global_config]: ./configure.html#global-settings
[configure_vardir]: ./configure.html#catalog-hash-conflict-debugging

What is Catalog Duplication?
-----

Every puppet agent run on a host results in a new catalog sent from
the puppetmaster to PuppetDB. Although catalogs can be very large,
typically there are 1000 or more resources and their relationships in
each of the catalogs. Storing each of these resources and
relationships is a fairly I/O intensive operation on the database used
by PuppetDB. Puppet agent runs happen frequently (i.e. every 30
minutes) but, most of the runs will not result in a catalog with
different resources or different relationtships. Typically only a
manually initiated change by an admin will result in an updated
catalog. PuppetDB stores catalogs with this use case in mind. PuppetDB
will MD5 hash the contents of a catalog and will match that hash with
the hash of the previously received catalog for that host. Only when
these hashes don't match will PuppetDB incur the extra cost/overhead
of storing the new catalog (with all of it's resources and edges). The
catalog duplication rate can be found on the PuppetDB dashboard as a
percentage. More information on the dashboard can be found in the
[Maintaining and Tuning guide][[maintaining_tuning]. Typically, this
catalog duplication rate should be over 90%. If this percentage is
low, there will be a significantly higher I/O load on the database.
This load can lead to slowness and resource contention for the
database and PuppetDB itself.

What Causes Catalogs to Hash Differently?
-----

Anything that changes, adds or removes a resource or a relationship
will cause the catalog for that host to hash differently. Most
commonly, this is caused by an admin initiated change to a host (i.e.
adding a new package to the configuration of a web server). Prior to
PuppetDB 1.6, this could also be caused by a subtle reordering of
resources or properties. One example of that scenario is in a two
puppetmaster setup, one puppetmaster would send it's resources in a
different order than the other puppetmaster. This would cause the
catalogs to hash differently, even though their content was the same.
This issue has been fixed in PuppetDB 1.6.

How Can I Detect the Cause of Catalogs Hashing Differently?
-----

Unfortunately, diagnosing why a catalog is hashing differently can be
difficult. For starters, the hashes look something like
`b4199f8703c5dc208054a62203db132c3d12581c` and by design, a small
change in the catalog results in a completely different hash. To help
troubleshoot these scenarios, PuppetDB 1.6 includes a catalog hash
debugging feature. To turn this on, set
[catalog-hash-conflict-debugging][configure_catalog_debugging] to true
by adding the following line to [the global PuppetDB
config][global_config] and restart PuppetDB:

    [global]
    vardir = /var/lib/puppetdb
    ...
    catalog-hash-conflict-debugging=true

This will create a new directory `debug/catalog-hashes` under the
[`vardir` directory][configure_vardir]. When this config option is
specified, each time the catalog for a given host doesn't match the
catalog previously stored for that host, it will output that catalog.
There will be five files written for each catalog hash that doesn't
match. All of the file names are of the format:
<hostname>_<UUID>_<name of content>.<ext>. The files that will be most useful in
detecting the problem are `*old-catalog.json` and `*new-catalog.json`.
These two files contain a pretty-printed version of the catalog in
JSON format. Diffing these two files is probably the easiest way to
detect differences in the two catalogs. There is also a metadata file
with the suffix `catalog-metadata.json` that includes the original
hash value, the new hash value and the full paths to all the files
outputted for debugging. The other two debugging files are more useful
for PuppetDB developers and are just the old/new catalogs in an EDN
format.