---
title: "PuppetDB 3.2: Migrating data"
layout: default
canonical: "/puppetdb/latest/migrate.html"
---

Exporting data from an existing PuppetDB database
------

**Note:** The import/export tools are only intended to migrate data between PuppetDB instances of the same version. Cross-version migrations may succeed, but are not supported.

If you've been trying out PuppetDB using the embedded database and are ready to move to a production environment backed by PostgreSQL, or if you'd simply like to move your data from one PostgreSQL database to another, you can use the `puppetdb export` command (which is available in your `/usr/sbin` directory for PuppetDB versions 1.2 and above). All you'll need to do is run:

    $ sudo puppetdb export --outfile ./my-puppetdb-export.tar.gz

This command is intended to be run on the PuppetDB server, and assumes that PuppetDB is accepting plain-text HTTP connections on `localhost` port `8080` (which is PuppetDB's default configuration). If you've modified your PuppetDB HTTP configuration, you can specify a different hostname and port on the command line. For more information, run:

    $ sudo puppetdb export --help

While not required, we recommended running this tooling while there is no activity on your existing PuppetDB, which will ensure that your data snapshot is consistent. Also, be aware that this tool can add load to your production system.

The generated tarball will contain a backup of all of your current catalogs (including exported resources), reports, and facts.

Importing data to a new PuppetDB database
------

Once you have an export tarball and a new PuppetDB server up and running, use the `puppetdb-import` command to import your data. (This command is available in your `/usr/sbin` directory in PuppetDB versions 1.2 and above.) The syntax will look something like this:

    $ sudo puppetdb import --infile ./my-puppetdb-export.tar.gz

This command is intended to be run on the new PuppetDB server, and assumes that PuppetDB is accepting plain-text HTTP connections on `localhost` port `8080` (which is PuppetDB's default configuration). If you've modified your PuppetDB HTTP configuration, you can specify a different hostname and port on the command line. For more information, run:

    $ sudo puppetdb import --help
