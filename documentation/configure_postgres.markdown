# Using PostgreSQL

Before using the PostgreSQL backend, you must set up a PostgreSQL server. Note
that users installing PuppetDB via [the module][module] will already have
PostgreSQL configured properly and these steps should not be necessary.

**Please note,** if you are not using the module, and you are running
PostgreSQL on a different server from your PuppetDB node, you must [configure
an SSL connection between PuppetDB and PostgreSQL](#using-ssl-with-postgresql)
to secure your database connections. Otherwise your PuppetDB communication with
Postgres will be going over a network in plaintext.

If you are not using the module, you will need to configure a PostgreSQL
server, version 9.6 or newer, to include a user and an empty database for
PuppetDB, and the server must accept incoming connections to that database as
that user.  PostgreSQL connections and authentication are discussed
[here](https://www.postgresql.org/docs/9.6/static/auth-pg-hba-conf.html), and
setting up users and databases is discussed in the [Getting
Started](https://www.postgresql.org/docs/9.6/static/tutorial-start.html)
section of the [PostgreSQL
manual](https://www.postgresql.org/docs/9.6/static/index.html).

Completely configuring PostgreSQL is beyond the scope of this guide, but a
example setup is described below. First, you can create a user and database as
follows. Then, to have a secure installation you must create a read-only user
to configure the `[read-database]` config section. This limits the postgresql
permissions of PuppetDB queries and prevents them from writing, updating,
or deleting any data.

```
sudo -u postgres sh
createuser -DRSP puppetdb
createuser -DRSP puppetdb_read
createdb -E UTF8 -O postgres puppetdb
psql puppetdb -c 'revoke create on schema public from public'
psql puppetdb -c 'grant create on schema public to puppetdb'
psql puppetdb -c 'alter default privileges for user puppetdb in schema public grant select on tables to puppetdb_read'
psql puppetdb -c 'alter default privileges for user puppetdb in schema public grant usage on sequences to puppetdb_read'
psql puppetdb -c 'alter default privileges for user puppetdb in schema public grant execute on functions to puppetdb_read'
```

If you already have PuppetDB installed and running and are adding a read-only
user, you will need to grant the same privileges as above to existing objects.

```
psql puppetdb -c 'grant select on all tables in schema public to puppetdb_read'
psql puppetdb -c 'grant usage on all sequences in schema public to puppetdb_read'
psql puppetdb -c 'grant execute on all functions in schema public to puppetdb_read'
exit
```

Particularly if you plan to run more than one PuppetDB instance
connecting to the same database, we recommend you also
[add and use a "migrator" user](#coordinating-database-migrations)

Install the [`pg_trgm`][pg_trgm] extension. PuppetDB makes use of this
extension to improve the performance of queries that use regular expression
filters (e.g. `certname ~ "abc\d+.example.com"`). This may require installing
the `postgresql-contrib` (or equivalent) package, depending on your
distribution:

    $ sudo -u postgres sh
    $ psql puppetdb -c 'create extension pg_trgm'
    $ exit

Next, you will most likely need to modify the `pg_hba.conf` file to
allow for MD5 authentication from at least localhost. To locate the
file you can either issue a `locate pg_hba.conf` command (if your
distribution supports it) or consult your distribution's documentation
for the PostgreSQL `confdir`.

The following example `pg_hba.conf` file allows MD5 authentication
from localhost for both IPv4 and IPv6 connections:

    # TYPE  DATABASE   USER   CIDR-ADDRESS  METHOD
    local   all        all                  md5
    host    all        all    127.0.0.1/32  md5
    host    all        all    ::1/128       md5

Restart PostgreSQL and ensure you can log in by running:

    $ sudo service postgresql restart
    $ psql -h localhost puppetdb puppetdb

To configure PuppetDB to use this database, put the following in the
`[database]` section:

    subname = //<HOST>:<PORT>/<DATABASE>
    username = <USERNAME>
    password = <PASSWORD>

Replace `<HOST>` with the DB server's hostname. Replace `<PORT>` with
the port on which PostgreSQL is listening. Replace `<DATABASE>` with
the name of the database you've created for use with PuppetDB.

## Using SSL With PostgreSQL

It's possible to use SSL to protect connections to the database. There
are several extra steps and considerations when doing so; see the
[PostgreSQL SSL setup page][postgres_ssl] for complete details.

You have two options for setting up an SSL connection to Postgres, we recommend
using certificates to setup and authenticate an SSL connection between your
Postgres and PuppetDB. It is also possible to use password authentication, but
this has many limitations. Firstly, your PostgreSQL database username and
password will be stored in plaintext in your PuppetDB configuration files.
Additionally, neither Postgres nor PuppetDB will be verifying the identity of
the other server, meaning that anyone who can read that plaintext password will
be able to create database connections to Postgres.

## Coordinating database migrations

If you plan to run more than one PuppetDB instance connected to the
same database, you must ensure that two instances do not attempt to
upgrade the database simultaneously.  Further, you should also ensure
that a PuppetDB server never tries to use a database whose migration
level (data format version) differs from the one it expects.

PuppetDB will refuse to start if it detects an unexpected migration
level, which covers many cases, but won't help, for example, if a new
version of PuppetDB is started while older versions are still running.

One direct solution, using upgrades as an example, is to just make
sure to stop all of your PuppetDB instances, then run one instance of
the newer version to perform any necessary upgrade via

    puppetdb upgrade -c .../normal-config.ini

Once that's finished, relaunch all of your instances using the
newer version of PuppetDB.

PuppetDB can also be configured to attempt to automatically guard you
against these risks.  To do so, first make sure all but one of your PuppetDB
instances are configured with `[database]` [migrate option](#migrate)
set to `false` in the config file.

This will prevent PuppetDB from attempting to upgrade the database at
startup (it will just quit on a mismatch instead).  You will of course
need to set it to `true` (the default) in the config file of the
instance you want to perform your migrations (either at startup or via
the `upgrade` subcommand shown above).

Setting `migrate` to false helps prevent unexpected migrations, but it
doesn't prevent a migration from starting while other (soon to be
invalid/out-of-date) PuppetDB instances continue to access the
database.  That's true even though newer PuppetDB versions have a
check to prevent them from creating new connections to an unrecognized
database version because PuppetDB can continue to use any connections
that are already open (and either active or waiting in the connection
pool).

To help prevent all acccess to an unexpected database version, you can
provide PuppetDB with a separate, suitably configured PostgreSQL user
(role), for migrations.  That role must have the ability to grant and
revoke connection privileges to/from the normal PuppetDB database
user, and it must also be allowed to terminate the normal user's
existing connections.  One way to arrange that is to do sometthing
like this after creating the `puppetdb` user as described above:

```
sudo -u postgres sh
createuser -DRSP puppetdb_migrator
psql puppetdb -c 'revoke connect on database puppetdb from public'
psql puppetdb -c 'grant connect on database puppetdb to puppetdb_migrator with grant option'

psql puppetdb -c 'set role puppetdb_migrator; grant connect on database puppetdb to puppetdb'
psql puppetdb -c 'set role puppetdb_migrator; grant connect on database puppetdb to puppetdb_read'
psql puppetdb -c 'grant puppetdb to puppetdb_migrator'
exit
```

Then specify `puppetdb_migrator` as the
[migrator-username](#migrator-username) and set the
[migrator-password](#migrator-password) as described below.

See the [migration coordination documentation][migration_coordination]
for a more detailed explanation of the process.

