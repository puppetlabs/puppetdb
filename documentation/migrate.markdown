---
title: "PuppetDB 1.3 » Migrating Data"
layout: default
canonical: "/puppetdb/latest/migrate.html"
---

Migrating from ActiveRecord storeconfigs
-----

If you're using exported resources with ActiveRecord storeconfigs, you may want to migrate your existing data to PuppetDB before connecting the master to it. This will ensure that whatever resources were being collected by the agents will still be collected, and no incorrect configuration will be applied.

The existing ActiveRecord data can be exported using the `puppet storeconfigs export` command, which will produce a tarball that can be consumed by PuppetDB. Because this command is intended only to stop nodes from failing until they have check into PuppetDB, it will only include exported resources, excluding edges and facts.

NOTE: in order for this to work properly, you need to make sure you've run this command and generated the export tarball *prior* to configuring your master for PuppetDB.

Once you've run this command and generated an export tarball, you should follow the instructions below to import the tarball into your PuppetDB database.

Exporting data from an existing PuppetDB database
------

If you've been trying out PuppetDB using the embedded database and are ready to move to a production environment backed by PostgreSQL, or if you'd simply like to move your data from one PostgreSQL database to another one, you can use the `puppetdb-export` command (which is available in your `/usr/sbin` directory for versions of PuppetDB >= 1.2).  All you'll need to do is run a command like this:

    $ sudo puppetdb-export --outfile ./my-puppetdb-export.tar.gz

This command is intended to be run on the PuppetDB server, and assumes that PuppetDB is accepting plain-text HTTP connections on `localhost` port `8080` (which is PuppetDB's default configuration). If you've modified your PuppetDB HTTP configuration, you can specify a different hostname and port on the command line.  For more info, run:

    $ sudo puppetdb-export --help

While its not required it is recommended you run this tooling while there is no activity on your existing PuppetDB to ensure your data snapshot is consistent. Also the tool can put load on your production system, so you should plan for this before running it.

The generated tarball will contain a backup of all of your current catalog data (including exported resources) and all you report data. At this time fact data exporting is not supported.

Exporting data from a version of PuppetDB prior to 1.2
------

The `puppetdb-export` and `puppetdb-import` tools were added to PuppetDB in version 1.2.  If you need to export data from an older version of PuppetDB, the easiest way to do so is to upgrade your existing PuppetDB to at least version 1.2 and then use the `puppetdb-export` tool.

Importing data to a new PuppetDB database
------

Once you have an export tarball and a new PuppetDB server up and running that you would like to import your data into, use the `puppetdb-import` command to do so.  (This command is available in your `/usr/sbin` directory in versions of PuppetDB >= 1.2.) The syntax will look something like this:

    $ sudo puppetdb-import --infile ./my-puppetdb-export.tar.gz

This command is intended to be run on the new PuppetDB server, and assumes that PuppetDB is accepting plain-text HTTP connections on `localhost` port `8080` (which is PuppetDB's default configuration).  If you've modified your PuppetDB HTTP configuration, you can specify a different hostname and port on the command line.  For more info, run:

    $ sudo puppetdb-import --help

