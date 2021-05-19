---
title: "Coordinating database migrations"
layout: default
canonical: "/puppetdb/latest/migration_coordination.html"
---
# Coordinating database migrations

[config]: ./configure.markdown

By default at startup PuppetDB will attempt to perform any database
updates that might be needed.  If there is a possibiity that multiple
PuppetDB instances could run at the same time, it's important to
coordinate the update process so that only one server attempts to perform
updates.  The [configuration documentation][config#coordinating-database-migrations]
explains how to arrange that, and the broader context, but not the
detailed process, which is described here.

Assuming all of the PuppetDB instances have been configured
appropriately, then only one will have `[database] migrate` set to
true, so that it will be the only one that should attempt a migration.
That will guard against concurrent migration attempts, but doesn't
help if the servers are misconfigured, and it doesn't do anything to
protect against another concern, the possibility that PuppetDB
instances might try to operate on a database that's at the incorrect
migration level during normal operations.

This might happen because, for example, a newer version of PuppetDB
has migrated the database to a version they don't understand, or
because they're not configured to perform a migration and the database
is too old.  And note that these concerns apply all of the time, not
just at startup, since the database might accidentally be migrated
while older PuppetDB instances are still running.

In an attempt to guard against all of these possibilities, PuppetDB
does the following:

When acting as a migrator (`migrate = true`)
-------------------------------------------------

* Connects to the database as the
  [migrator-username][config#migrator-username] using a completely
  independent connection pool.

* Begins a transaction.

* Grabs an exclusive migration lock.

* Checks to see if any migrations are required, and if not, ends the
  transaction, releasing the lock, and then proceeds with normal
  operations.

* If migrations *are* required, proceeds as follows.

* Revokes connection privileges from the normal
  [username][config#username].  This prevents any new connections from
  being established.

* Terminates any existing database connections from the normal
  [username][config#username].  The revocation above may not affect
  them.

* [Changes its role](https://www.postgresql.org/docs/11/sql-set-role.html)
   to the normal [username][config#username] so that all new database objects
   will be owned by the normal user.  If nothing else, this ensures
   the normal user will be able to drop those partitions during
   routine garbage collection (e.g. for reports).

* Performs all the required migrations.

* Commits the transaction, releasing the lock.

* Restores the normal user's connection privileges.

* Resumes normal operations.

All PuppetDB instances, including a migrator after migration
------------------------------------------------------------

* Always connects to the database as the normal
  [username][config#username] using the routine read and write pools.

* Sets a [HikariCP connectionInitSql](https://github.com/brettwooldridge/HikariCP#infrequently-used)
  guard that prevents any new connections to a database that isn't at
  the correct migration level by examining the levels in the
  schema_migrations table.

* Establishes a [periodic check][config#schema-check-interval] that
  will shut PuppetDB down if it detects a database that's either newer
  or older than it's prepared to handle.  This also makes sure that
  PuppetDB won't continue using any existing (pool) connections to the
  database (since the `connectionInitSql` check above only prevents
  new connections).

  When a version mismatch is detected, exits with a with a status of
  77 (ASCII `M`) if the migration level is too new, and 109 (ASCII
  `m`) if it's too old.  (Note: in versions before 6.10 the exit
  statuses are only required to be non-zero, not necessarily `m` or
  `M`).
