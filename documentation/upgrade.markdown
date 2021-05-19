---
title: "Upgrading PuppetDB"
layout: default
---

# Upgrading PuppetDB

[dashboard]: ./maintain_and_tune.markdown#monitor-the-performance-dashboard
[connect_server]: ./connect_puppet_server.markdown
[connect_apply]: ./connect_puppet_apply.markdown
[tracker]: https://tickets.puppetlabs.com/browse/PDB
[start_source]: ./install_from_source.markdown#step-4-start-the-puppetdb-service
[plugin_source]: ./connect_puppet_server.markdown#on-platforms-without-packages
[module]: ./install_via_module.markdown
[puppetdb3]: /puppetdb/3.2/migrate.html
[versioning]: ./versioning_policy.markdown#upgrades

## Checking for updates

PuppetDB's [performance dashboard][dashboard] displays the current version in
the upper right corner. It also automatically checks for updates and will show a
link to the newest version under the version indicator if your deployment is out
of date.

## Migrating existing data

If you are not planning to change your underlying PuppetDB database
configuration prior to upgrading, you don't need to worry about migrating your
existing data: PuppetDB will handle this automatically. However, if you plan to
switch to a different database, you should export your existing data prior to
changing your database configuration, but you must use PuppetDB 3.x to do so.
Please consult the [Migrating Data][puppetdb3] for more information.

## Upgrading with the PuppetDB module

If you [installed PuppetDB with the module][module], you only need to do the
following to upgrade:

1. Make sure that the Puppet Server has an updated version of the
   [puppetlabs-puppetdb](https://forge.puppetlabs.com/puppetlabs/puppetdb)
   module installed.
2. If you imported the official packages into your local package repositories,
   import the new versions of the PuppetDB and PuppetDB-termini packages.
3. Change the value of the `puppetdb_version` parameter for the `puppetdb` or
   `puppetdb::server` and `puppetdb::master::config` classes, unless it was set
   to `latest`.
4. If you are doing a large version jump, trigger a Puppet run on the PuppetDB
   server before the Puppet Server has a chance to do a Puppet run. (It's
   possible for a new version of the PuppetDB-termini to use API commands
   unsupported by old PuppetDB versions, which would cause Puppet failures until
   PuppetDB was upgraded, but this should be very rare.)

## Manually upgrading PuppetDB

### What to upgrade

When a new version of PuppetDB is released, you will need to upgrade:

1. PuppetDB itself
2. The [PuppetDB-termini][connect_server] on every Puppet Server (or
   [every node][connect_apply], if using a standalone deployment).

You should **upgrade PuppetDB first.** Because PuppetDB will be down for a few
minutes during the upgrade and Puppet Server will not be able to serve catalogs
until it comes back, you should schedule upgrades during a maintenance window
during which no new nodes will be brought online.

If you upgrade PuppetDB without upgrading the PuppetDB-termini, your Puppet
deployment should continue to function identically, with no loss of
functionality. However, you may not be able to take advantage of new PuppetDB
features until you upgrade the PuppetDB-termini.

### Upgrading PuppetDB

**On your PuppetDB server:** stop the PuppetDB service, upgrade the PuppetDB
package, then restart the PuppetDB service.

    $ sudo puppet resource service puppetdb ensure=stopped
    $ sudo puppet resource package puppetdb ensure=latest
    $ sudo puppet resource service puppetdb ensure=running

#### On platforms without packages

If you installed PuppetDB by running `rake install`, you should obtain a fresh
copy of the source, stop the service, and run `rake install` again. Note that
this workflow is not well tested; if you run into problems, please report them
on the [PuppetDB issue tracker][tracker].

If you are running PuppetDB from source, you should stop the service, replace
the source, and
[start the service as described in the advanced installation guide][start_source].

### Upgrading the terminus plugins

**On your Puppet Servers:** upgrade the PuppetDB-termini package, then
restart the Puppet Server's web server:

    $ sudo puppet resource package puppetdb-termini ensure=latest

The command to restart the Puppet Server will vary, depending on which web
server you are using.

#### On platforms without packages

Obtain a fresh copy of the PuppetDB source, and follow
[the instructions for installing the termini][plugin_source].

The command to restart the Puppet Server will vary, depending on which web
server you are using.

### Upgrading across multiple major versions

As stated by the [versioning policy][versioning], you cannot "skip"
major versions of PuppetDB when upgrading.  For example, if you need
to upgrade from PuppetDB 4.2.3 to 6.0.0, you must run some version of
PuppetDB 5 at least long enough for it to upgrade your existing data.

The upgrade subcommand can help with this.  When specified, PuppetDB
will quit as soon as it has finished all of the necessary work:

    $ puppetdb upgrade -c /path/to/config.ini

## Truncate your reports table

Some PuppetDB versions contain long database migrations that can be avoided by
deleting all the reports and resource events from your database. Currently this
is true for PuppetDB 6.8.0 and 6.10.0. Please note that PuppetDB migrations are
cumulative, so if you're upgrading from PuppetDB 6.7.0 to 6.11.0 your upgrade
will contain two long running database migrations that can be avoiding by
deleting your reports.

**WARNING:** This is a permanent destructive action and should be done with care.

Truncating the reports table will delete all your reports and all their
associated resource events.  This is primarily helpful for users with large
databases when upgrades involve expensive database migrations, such as
upgrading PostgreSQL versions.

### Monolithic installs

For standard installs, where PuppetDB and Postgres run on the same machine, and
you use Puppet's default user and database names you can delete your reports
and resource events by running `/opt/puppetlabs/bin/puppetdb delete-reports` as
root.

### Non-default user/database names, PostgreSQL port, or `psql` location

If you are not running a standard install you can follow the general outline
below.  Be sure to run `puppetdb delete-reports --help` to see if you need to
customize any of the user or database names for your own install.

### Postgres on another server

The `delete-reports` subcommand lives on the server that runs PuppetDB at
`/opt/puppetlabs/server/apps/puppetdb/cli/apps/delete-reports`. In order for
this command to work, you'll need to manually transfer it to the server that is
running PuppetDB's PostgreSQL and execute it there. It will fail to stop the
PuppetDB service, because one doesn't exist there, but it will continue and
delete the reports anyways.

### No `delete-reports` subcommand exists

If no `delete-reports` subcommand exists, you are on an older version of PuppetDB,
but deleting your reports is just as important when upgrading to a version
later than or equal to PuppetDB 6.8.0 or 6.10.0. If this is the case, you
should delete your reports manually.

First, stop the PuppetDB service where it is running.
```
service puppetdb stop
```

Then, on your PostgreSQL server, write the following SQL for your PuppetDB version to
a file named `/tmp/delete-reports.sql` and then set it to be owned by the postgres user
(`chown postgres:postgres /tmp/delete-reports.sql`).

If you are upgrading **from** a PuppetDB version less than 6.8.0 that does not
have the `delete-reports` subommand, your `delete-reports.sql` file is,
```
BEGIN TRANSACTION;

ALTER TABLE certnames DROP CONSTRAINT IF EXISTS certnames_reports_id_fkey;
UPDATE certnames SET latest_report_id = NULL;
TRUNCATE TABLE reports CASCADE;

ALTER TABLE certnames
  ADD CONSTRAINT certnames_reports_id_fkey
    FOREIGN KEY (latest_report_id) REFERENCES reports(id) ON DELETE SET NULL;

COMMIT TRANSACTION;
```

If you are upgrading **from** a PuppetDB version greater than or equal to 6.8.0
but less than PuppetDB 6.10.0, your `delete-reports.sql` file will need to contain
```
BEGIN TRANSACTION;

ALTER TABLE certnames DROP CONSTRAINT IF EXISTS certnames_reports_id_fkey;
UPDATE certnames SET latest_report_id = NULL;

DO $$ DECLARE
    r RECORD;
BEGIN
    FOR r IN (SELECT tablename FROM pg_tables WHERE tablename LIKE 'resource_events_%') LOOP
        EXECUTE 'DROP TABLE ' || quote_ident(r.tablename);
    END LOOP;
END $$;

TRUNCATE TABLE reports CASCADE;

ALTER TABLE certnames
  ADD CONSTRAINT certnames_reports_id_fkey
    FOREIGN KEY (latest_report_id) REFERENCES reports(id) ON DELETE SET NULL;

COMMIT TRANSACTION;
```

Now that the file exists and is owned by the `postgres` user, run
```
su - postgres -s /bin/bash -c "psql -d puppetdb -f /tmp/delete-reports.sql"
```
