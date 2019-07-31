---
title: "Support and Troubleshooting Guide"
layout: default
---

[commands]: ./api/command/v1/commands.html#list-of-commands
[threads]: ./configure.html#threads
[pgstattuple]: http://www.postgresql.org/docs/9.6/static/pgstattuple.html
[pgtune]: https://github.com/gregs1104/pgtune
[postgres-config]: http://www.postgresql.org/docs/current/static/runtime-config-resource.html
[fact-precedence]: {{facter}}/custom_facts.html#fact-precedence
[dbvis]: https://www.dbvis.com/
[stockpile]: https://github.com/puppetlabs/stockpile

This is a technical guide for troubleshooting PuppetDB (PDB) and understanding
its internals.

## PDB Architectural Overview

Data stored in PDB flows through four components.  It's passed from
the terminus to a filesystem queue, from which it is picked up later
by the application, and then recorded in the database.  It's not
unusual for the terminus, the application managing the queue, and the
database to reside on three separate machines.

### Terminus

The terminus resides on the Puppet master and redirects agent data to
PDB in the form of "commands". PDB has four commands, as described in the
[commands documentation][commands].

### Message Queue

Currently, all of the commands sent from the terminus to PDB are
deferred to a filesystem queue for later processing, at which point
they will be submitted to the database in roughly the order received.

Previous versions of PDB stored deferred messages in ActiveMQ, which
conglomerated them in opaque binary files inside `vardir`/mq.  Now PDB
stores the messages via [stockpile][stockpile], which records each
message as a normal file inside `vardir`/stockpile/cmd/q, and while
it's unlikely to be necessary, it's safe to manually remove queue
commands when PDB is not running.

The message names are designed to provide some information about their
content.  For example:

    stockpile/cmd/q/167-1478013718598_facts_5_somehost.json

Contains a version 5 "replace facts" command for certname "somehost"
that was received at 1478013718598 milliseconds since the epoch
(1970-01-01 UTC).  The 167 is a stockpile sequence number, and as
suggested by the .json extension, the command files are just plain
text JSON representations of the incoming commands.

Given this arrangement, you can use normal filesystem commands for
rough examinations of the queue.  For example, something like this

    find cmd/q | grep -cE '^cmd/q/[0-9]+-[0-9]+_facts_'

should provide a count of "replace facts" commands in the queue, and
something like this:

    find cmd/q -printf "%s %p\n" | sort -n | tail

should list the largest commands in the queue.

Note that the certname may be altered to accommodate filesystem
restrictions.  Currently that means replacing the characters "/", ":",
"\", and 0 with "-", and truncating the certname so that it's UTF-8
encoding never exceeds about 255 characters.  A truncated certname
will be followed by an underscore and a hash of the full certname.
For example:

    stockpile/cmd/q/167-1478013718598_facts_5_LONGNAME_HASH.json

As a result of this, PDB expects the `vardir` filesystem to be able to
handle all of your certnames, UTF-8 encoded, as filenames, excepting
of course, any characters that are translated to dashes as mentioned
above.

PDB also expects the `vardir` filesystem to be able to handle
filenames up to a maximum of 255 bytes, although the actual queue
message filename lengths will depend on your certnames.  Filesystems
like ext4 and xfs should work fine.

In addition to the message files, you may also see some files in the
queue whose names begin with "tmp-".  These are just temporary files
created during message delivery and can be ignored.  Under normal
circumstances, they should be very short lived, but if they were to
accumulate (likely indicating some other problem), PDB will attempt to
clean them up when restarted.

### Command processing

PDB processes the queue concurrently with the number of threads specified [in
configuration][threads]. Commands are processed differently depending on their
type:

* `store report`: store report commands are relatively inexpensive, consisting
  mainly of inserting a row in the reports table.
* `replace catalog`: When a replace catalog command is received, PDB will first
  check if a more recent catalog already exists in the database for the node.
  If so, the catalog is discarded and no action is taken. If not, PDB will
  execute a diff of the catalog in hand and the catalog in the database, and
  insert only the resources and edges that have changed.
* `replace facts`: PDB stores facts as key-value associations between "paths"
  and "values". The term "path" refers to a specific route from the root of a
  tree (e.g structured fact) to a leaf value, and "value" refers to the leaf
  value itself. Conceptually, every fact is stored as a tree. To
  illustrate, the fact

      "foo" => "bar"

  is stored as

      "foo" => "bar"

  while the fact

      "foo" => {"a" => "bar", "b" => "baz"}

  is stored as

      "foo#~a" => "bar"
      "foo#~b" => "baz"

  For the array case, the fact

      "foo" => ["bar", "baz"]

  is stored as

      "foo#~0" => "bar"
      "foo#~1" => "baz"

  The same rules apply recursively for larger structures. When a replace facts
  command is received, PDB will compare the fact in hand against the paths
  and values in the database, add whatever new paths/values are required, and
  delete any invalidated pairs.

* `deactivate node`: The deactivate node command just updates a column in the
  certnames table, and isn't likely to be the source of performance issues.

### Database

PDB uses PostgreSQL. The best way to get familiar with the schema is
to generate an ERD diagram from your database and investigate for yourself on a running
instance via the psql interactive console. [DB Visualizer][dbvis] is an excellent tool for this.
In addition, the PDB team is available for questions on the mailing list and in #puppet and
#puppet-dev on freenode to answer any questions.

## PDB Diagnostics

When any issue is encountered with PDB, the first priority should be
collecting and inspecting the following:

* PDB logs (puppetdb.log, puppetdb-daemon.log, .hprof files in the log
  directory)
* PostgreSQL logs
* Screenshot of PDB dashboard
* atop output on PDB system

### PDB Logs

Search the PDB logs for recurring errors that line up with the timing of
the issue. Some common errors that may show in the PDB logs
are:

* database constraint violations: These will appear from time to time in most
  installs due to concurrent command processing, but if they occur frequently
  and in relation to multiple nodes it usually indicates an issue. Note that
  when a command fails to process due to a constraint violation, it will be
  retried 16 times over a period of a day or so, with the retry count displayed
  in the log. Seeing 16 retries of a single command indicates an issue with the
  command, but not necessarily with the application.

* Out of memory errors: PDB can crash if it receives a command too large
  for its heap. This can be trivially fixed by raising the Xmx setting in the
  JAVA_ARGS entry in /etc/sysconfig/puppetdb on redhat or
  /etc/defaults/puppetdb on Debian derivatives. Usually though, crashes due to
  OOMs indicate that PDB is getting used in ways that it should not be, and
  it's important to identify and inspect the commands that cause the crash to
  figure out whether there is some misuse of Puppet that can be corrected.

  The most common causes of OOMs on command processing are blobs of binary data
  stored in catalog resources, huge structured facts, and large numbers of log
  entries within a report. Out of memory errors should generate a heap dump
  suffixed with .hprof in the log directory, which should contain the offending
  command.

### PostgreSQL logs

Have the following settings or sensible equivalents enabled in postgresql.conf
prior to log examination:

    log_line_prefix = '%m [db:%d,sess:%c,pid:%p,vtid:%v,tid:%x] '
    log_min_duration_statement = 5000

Check the postgres logs for:

* Postgres errors (marked "ERROR") coming from the puppetdb database
* Slow queries

Slow queries are the main concern here, since most errors here have already
been seen in the PDB logs. Slow queries frequently occur on deletes related to
garbage collection and in queries against event-counts and
aggregate-event-counts (which will typically present in Puppet Enterprise as
slow page loads on the event inspector). Garbage collection deletes only run
periodically, so some degree of slowness there is not generally an issue.

For slow queries connected to queries against PDB's REST API, the two most
common exacerbators are insufficient memory allocated to PostgreSQL and table
bloat. In either case, the first step should be to copy the query from the log
and get the plan Postgres is choosing by looking at the output of `explain
analyze <query>;` in psql. This will tell you which operations and tables the
query is spending the most time on, after which you can look for conspicuous
bloat using the pgstattuple module:

    create extension pgstattuple;
    select * from pg_stat_tuple('reports'); -- (in the case of reports)

Familiarize yourself with the pgstattuple module with the [postgres
documentation][pgstattuple].

On the memory usage side, the main tipoff will be queries with explain plans
that mention sorts on disk. To examine your options for optimization, you might
try running [pgtune][pgtune] against your postgresql.conf and examining the
configuration it chooses. Note that the pgtune output is likely not perfect for
your needs, as it assumes that PostgreSQL is the only application running on
your server.  It should be used as a guide rather than a prescription. The most
meaningful options to look at are typically `work_mem`, `shared_buffers`, and
`effective_cache_size`. Consult the [PostgreSQL documentation][postgres-config]
for information on these settings.

### PDB dashboard

There are a few things to watch for in the PDB dashboard:

* Low catalog duplication rate: PuppetDB includes some optimizations built on
  the assumption that the catalog for a given node changes relatively
  infrequently. Namely, when PuppetDB receives a catalog for a node that hashes
  to the same value as the node's previous catalog, PuppetDB will
  simply update the timestamp associated with the last catalog, rather than
  insert the data again. This works fine most of the time, but is failure prone
  in cases where aspects of the catalog are guaranteed to change on every run.
  For example, if the catalog contains a resource that embeds the current
  timestamp, the hashes will never match and additional work must be done to
  assess which resources need to be replaced. The catalog duplication rate
  metric in the dashboard shows the ratio of hash matches to catalogs received.
  Typically the duplication rate will be above 70%, and often above 90%. If
  your duplication rate is substantially lower than this, it may be worth
  investigating whether anything can be done to reduce the rate of change
  between runs.

* Deep command queue: Under sustainable conditions, the command queue depth
  should be in the neighborhood of 0-100 most of the time, with occasional
  spikes allowed. If the command queue is deeper than 10,000 for any extended
  period of time, your commands are being processed too slowly. Causes of slow
  command processing include:

  - large, particularly array-valued, structured facts
  - large commands in general
  - insufficient hardware

  Per the command-processing section above, array-valued structured facts are
  stored with the index of each element embedded in the fact path. Imagining an
  array of hashes, the fact

      "foo" => [{"bar" => "baz", "biz" => "qux"}, {"spam" => "eggs", "zig" => "zag}]

  is stored as

      "foo#~0#~bar" => "baz"
      "foo#~0#~biz" => "qux"
      "foo#~1#~spam" => "eggs"
      "foo#~1#~zig" => "zag"

  if an element is inserted at the front of the array, then for the fact to be
  updated all subsequent paths must be invalidated and recomputed, and
  invalidated paths must be deleted:

      "foo" => [{"alpha" => "beta", "gamma" => "delta"}, {"bar" => "baz", "biz" => "qux"}, {"spam" => "eggs", "zig" => "zag}]

  is stored as:

      "foo#~0#~alpha" => "beta"
      "foo#~0#~gamma" => "delta"
      "foo#~1#~bar" => "baz"
      "foo#~1#~biz" => "qux"
      "foo#~2#~spam" => "eggs"
      "foo#~2#~zig" => "zag"

  The worst case is a long array of large hashes, where a single replace-facts
  command can trigger the recomputation and deletion of thousands of paths.
  The mitigative solution is to change the top-level structure of the fact from
  an array to a hash, which may narrow the scope of the tree to which the
  recomputed paths are contained.  If this is infeasible, or if the fact in
  question is irrelevant to your needs, the fact may be overridden by creating
  a custom fact with the same name and weight 100. Refer to [fact
  precedence][fact-precedence] for examples.

### atop output

atop is a useful tool for determining which parts of a system are
bottlenecking PDB. Download atop via your package manager and consult the
manpage for definitive documentation. The default page shows disk, cpu, and
memory usage. If any of these appear out of the ordinary, you can get further
information by typing `d`, `s`, and `m` within atop.

## Contact Us

If none of the above lead to a solution, there is a good chance that others are
encountering your issue. Please contact us via the puppet-users mailing list or
on freenode in #puppet or #puppet-dev so we can update this document. If you
have general advice that this document does not include, feel free to submit a
pull request.
