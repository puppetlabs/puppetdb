---
title: "PuppetDB 1.1 » Release Notes"
layout: default
canonical: "/puppetdb/1.1/release_notes.html"
---

1.1.0
-----

Many thanks to the following people who contributed patches to this
release:

* Chris Price
* Deepak Giridharagopal
* Jeff Blaine
* Ken Barber
* Kushal Pisavadia
* Matthaus Litteken
* Michael Stahnke
* Moses Mendoza
* Nick Lewis
* Pierre-Yves Ritschard

Notable features:

* Enhanced query API

  A substantially improved version 2 of the HTTP query API has been added. This
  is located under the /v2 route. Detailed documentation on all the available
  routes and query language can be found in the API documentation, but here are
  a few of the noteworthy improvements:

  * Query based on regular expressions

    Regular expressions are now supported against most fields when querying
    against resources, facts, and nodes, using the ~ operator. This makes it
    easy to, for instance, find *all* IP addresses for a node, or apply a query
    to some set of nodes.

  * More node information

    Queries against the /v2/nodes endpoint now return objects, rather than
    simply a list of node names. These are effectively the same as what was
    previously returned by the /status endpoint, containing the node name, its
    deactivation time, as well as the timestamps of its latest catalog, facts,
    and report.

  * Full fact query

    The /v2/facts endpoint supports the same type of query language available
    when querying resources, where previously it could only be used to retrieve
    the set of facts for a given node. This makes it easy to find the value of
    some fact for all nodes, or to do more complex queries.

  * Subqueries

    Queries can now contain subqueries through the `select-resources` and
    `select-facts` operators. These operators perform queries equivalent to
    using the /v2/resources and /v2/facts routes, respectively. The information
    returned from them can then be correlated, to perform complex queries such
    as "fetch the IP address of all nodes with `Class[apache]`", or "fetch the
    `operatingsystemrelease` of all Debian nodes". These operators can also be
    nested and correlated on any field, to answer virtually any question in a
    single query.

  * Friendlier, RESTful query routes

    In addition to the standard query language, there are also now more
    friendly, "RESTful" query routes. For instance, `/v2/nodes/foo.example.com`
    will return information about the node foo.example.com. Similarly,
    `/v2/facts/operatingsystem` will return the `operatingsystem` of every node, or
    `/v2/nodes/foo.example.com/operatingsystem` can be used to just find the
    `operatingsystem` of foo.example.com.

    The same sort of routes are available for resources as well.
    `/v2/resources/User` will return every User resource, `/v2/resources/User/joe`
    will return every instance of the `User[joe]` resource, and
    `/v2/nodes/foo.example.com/Package` will return every Package resource on
    foo.example.com. These routes can also have a query parameter supplied, to
    further query against their results, as with the standard query API.

* Improved catalog storage performance

   Some improvements have been made to the way catalog hashes are computed for
   deduplication, resulting in somewhat faster catalog storage, and a
   significant decrease in the amount of time taken to store the first catalog
   received after startup.

* Experimental report submission and storage

  The 'puppetdb' report processor is now available, which can be used
  (alongside any other reports) to submit reports to PuppetDB for storage. This
  feature is considered experimental, which means the query API may change
  significantly in the future. The ability to query reports is currently
  limited and experimental, meaning it is accessed via /experimental/reports
  rather than /v2/reports. Currently it is possible to get a list of reports
  for a node, and to retrieve the contents of a single report. More advanced
  querying (and integration with other query endpoints) will come in a future
  release.

  Unlike catalogs, reports are retained for a fixed time period (defaulting to
  7 days), rather than only the most recent report being stored. This means
  more data is available than just the latest, but also prevents the database
  from growing unbounded. See the documentation for information on how to
  configure the storage duration.

* Tweakable settings for database connection and ActiveMQ storage

  It is now possible to set the timeout for an idle database connection to be
  terminated, as well as the keep alive interval for the connection, through
  the `conn-max-age` and `conn-keep-alive` settings.

  The settings `store-usage` and `temp-usage` can be used to set the amount of
  disk space (in MB) for ActiveMQ to use for permanent and temporary message
  storage. The main use for these settings is to lower the usage from the
  default of 100GB and 50GB respectively, as ActiveMQ will issue a warning if
  that amount of space is not available.

Behavior changes:

  * Messages received after a node is deactivated will be processed

    Previously, commands which were initially received before a node was
    deactivated, but not processed until after (for instance, because the first
    attempt to process the command failed, and the node was deactivated before
    the command was retried) were ignored and the node was left deactivated.
    For example, if a new catalog were submitted, but couldn't be processed
    because the database was temporarily down, and the node was deactivated
    before the catalog was retried, the catalog would be dropped. Now the
    catalog will be stored, though the node will stay deactivated. Commands
    *received* after a node is deactivated will continue to reactivate the node
    as before.

1.0.5
-----

Many thanks to the following people who contributed patches to this
release:

* Chris Price
* Deepak Giridharagopal

Fixes:

* Drop a large, unused index on catalog_resources(tags)

  This index was superseded by a GIN index on the same column, but the previous
  index was kept around by mistake. This should result in a space savings of
  10-20%, as well as a possible very minor improvement in catalog insert
  performance.

1.0.4
-----

Many thanks to the following people who contributed patches to this
release:

* Chris Price

Fixes:

* (#16554) Fix postgres query for numeric comparisons

  This commit changes the regex that we are using for numeric
  comparisons in postgres to a format that is compatible with both 8.4
  and 9.1.

1.0.3
-----

NOTE: This version was not officially released, as additional fixes
came in between the time we tagged this and the time we were going to
publish release artifacts.

Many thanks to the following people who contributed patches to this
release:

* Deepak Giridharagopal
* Nick Lewis
* Chris Price

Fixes:

* (#17216) Fix problems with UTF-8 transcoding

  Certain 5 and 6 byte sequences were being incorrectly transcoded to
  UTF-8 on Ruby 1.8.x systems. We now do two separate passes, one with
  iconv and one with our hand-rolled transcoding algorithms. Better
  safe than sorry!

* (#17498) Pretty-print JSON HTTP responses

  We now output more nicely-formatted JSON when using the PuppetDB
  HTTP API.

* (#17397) DB pool setup fails with numeric username or password

  This bug happens during construction of the DB connection pool. If
  the username or password is numeric, when parsing the configuration
  file they're turned into numbers. When we go to actually create the
  pool, we get an error because we're passing in numbers when strings
  are expected.

* (#17524) Better logging and response handling for version checks

  Errors when using the `version` endpoint are now caught, logged at a
  more appropriate log level, and a reasonable HTTP response code is
  returned to callers.


1.0.2
-----

Many thanks to the following people who contributed patches to this
release:

* Matthaus Owens

Fixes:

* (#17178) Update rubylib on debian/ubuntu installs

  Previously the terminus would be installed to the 1.8 sitelibdir for ruby1.8 or
  the 1.9.1 vendorlibdir on ruby1.9. The ruby1.9 code path was never used, so
  platforms with ruby1.9 as the default (such as quantal and wheezy) would not be
  able to load the terminus. Modern debian packages put version agnostic ruby
  code in vendordir (/usr/lib/ruby/vendor_ruby), so this commit moves the
  terminus install dir to be vendordir.

1.0.1
-----

Many thanks to the following people who contributed patches to this
release:

* Deepak Giridharagopal
* Nick Lewis
* Matthaus Litteken
* Chris Price

Fixes:

* (#16180) Properly handle edges between exported resources

  This was previously failing when an edge referred to an exported
  resource which was also collected, because it was incorrectly
  assuming collected resources would always be marked as NOT
  exported. However, in the case of a node collecting a resource which
  it also exports, the resource is still marked exported. In that
  case, it can be distinguished from a purely exported resource by
  whether it's virtual. Purely virtual, non-exported resources never
  appear in the catalog.

  Virtual, exported resources are not collected, whereas non-virtual,
  exported resources are. The former will eventually be removed from
  the catalog before being sent to the agent, and thus aren't eligible
  for participation in a relationship. We now check whether the
  resource is virtual rather than exported, for correct behavior.

* (#16535) Properly find edges that point at an exec by an alias

  During namevar aliasing, we end up changing the :alias parameter to
  'alias' and using that for the duration (to distinguish "our"
  aliases form the "original" aliases). However, in the case of exec,
  we were bailing out early because execs aren't isomorphic, and not
  adding 'alias'. Now we will always change :alias to 'alias', and
  just won't add the namevar alias for execs.

* (#16407) Handle trailing slashes when creating edges for file
  resources

  We were failing to create relationships (edges) to File resources if
  the relationship was specified with a different number of trailing
  slashes in the title than the title of the original resource.

* (#16652) Replace dir with specific files for terminus package

  Previously, the files section claimed ownership of Puppet's libdir,
  which confuses rpm when both packages are installed. This commit
  breaks out all of the files and only owns one directory, which
  clearly belongs to puppetdb. This will allow rpm to correctly
  identify files which belong to puppet vs puppetdb-terminus.


1.0.0
-----

The 1.0.0 release contains no changes from 0.11.0 except a minor packaging change.

0.11.0
-----

Many thanks to the following people who contributed patches to this
release:

* Kushal Pisavadia
* Deepak Giridharagopal
* Nick Lewis
* Moses Mendoza
* Chris Price

Notable features:

* Additional database indexes for improved performance

  Queries involving resources (type,title) or tags without much
  additional filtering criteria are now much faster. Note that tag
  queries cannot be sped up on PostgreSQL 8.1, as it doesn't have
  support for GIN indexes on array columns.

* Automatic generation of heap snapshots on OutOfMemoryError

  In the unfortunate situation where PuppetDB runs out of memory, a
  heap snapshot is automatically generated and saved in the log
  directory. This helps us work with users to much more precisely
  triangulate what's taking up the majority of the available heap
  without having to work to reproduce the problem on a completely
  different system (an often difficult proposition). This helps
  keep PuppetDB lean for everyone.

* Preliminary packaging support for Fedora 17 and Ruby 1.9

  This hasn't been fully tested, nor integrated into our CI systems,
  and therefore should be considered experimental. This fix adds
  support for packaging for ruby 1.9 by modifying the @plibdir path
  based on the ruby version. `RUBY_VER` can be passed in as an
  environment variable, and if none is passed, `RUBY_VER` defaults to
  the ruby on the local host as reported by facter. As is currently
  the case, we use the sitelibdir in ruby 1.8, and with this commit
  use vendorlibdir for 1.9. Fedora 17 ships with 1.9, so we use this
  to test for 1.9 in the spec file. Fedora 17 also ships with open-jdk
  1.7, so this commit updates the Requires to 1.7 for fedora 17.

* Resource tags semantics now match those of Puppet proper

  In Puppet, tags are lower-case only. We now fail incoming catalogs that
  contain mixed case tags, and we treat tags in queries as
  case-insensitive comparisons.

Notable fixes:

* Properly escape resource query strings in our terminus

  This fixes failures caused by storeconfigs queries that involve, for
  example, resource titles whose names contain spaces.

* (#15947) Allow comments in puppetdb.conf

  We now support whole-line comments in puppetdb.conf.

* (#15903) Detect invalid UTF-8 multi-byte sequences

  Prior to this fix, certain sequences of bytes used on certain
  versions of Puppet with certain versions of Ruby would cause our
  terminii to send malformed data to PuppetDB (which the daemon then
  properly rejects with a checksum error, so no data corruption would
  have taken place).

* Don't remove puppetdb user during RPM package uninstall

  We never did this on Debian systems, and most other packages seem
  not to as well. Also, removing the user and not all files owned by
  it can cause problems if another service later usurps the user id.

* Compatibility with legacy storeconfigs behavior for duplicate
  resources

  Prior to this commit, the puppetdb resource terminus was not setting
  a value for "collector_id" on collected resources.  This field is
  used by puppet to detect duplicate resources (exported by multiple
  nodes) and will cause a run to fail. Hence, the semantics around
  duplicate resources were ill-specified and could cause
  problems. This fix adds code to set the collector id based on node
  name + resource title + resource type, and adds tests to verify that
  a puppet run will fail if it collects duplicate instances of the
  same resource from different exporters.

* Internal benchmarking suite fully functional again

  Previous changes had broken the benchmark tool; functionality has
  been restored.

* Better version display

  We now display the latest version info during daemon startup and on the
  web dashboard.

0.10.0
-----

Many thanks to the following people who contributed patches to this
release:

* Deepak Giridharagopal
* Nick Lewis
* Matthaus Litteken
* Moses Mendoza
* Chris Price

Notable features:

* Auto-deactivation of stale nodes

  There is a new, optional setting you can add to the `[database]`
  section of your configuration: `node-ttl-days`, which defines how
  long, in days, a node can continue without seeing new activity (new
  catalogs, new facts, etc) before it's automatically deactivated
  during a garbage-collection run.

  The default behavior, if that config setting is ommitted, is the
  same as in previous releases: no automatic deactivation of anything.

  This feature is useful for those who have a non-trivial amount of
  volatility in the lifecycles of their nodes, such as those who
  regularly bring up nodes in a cloud environment and tear them down
  shortly thereafter.

* (#15696) Limit the number of results returned from a resource query

  For sites with tens or even hundreds of thousands of resources, an
  errant query could result in PuppetDB attempting to pull in a large
  number of resources and parameters into memory before serializing
  them over the wire. This can potentially trigger out-of-memory
  conditions.

  There is a new, optional setting you can add to the `[database]`
  section of your configuration: `resource-query-limit`, which denotes
  the maximum number of resources returnable via a resource query. If
  the supplied query results in more than the indicated number of
  resources, we return an HTTP 500.

  The default behavior is to limit resource queries to 20,000
  resources.

* (#15696) Slow query logging

  There is a new, optional setting you can add to the `[database]`
  section of your configuration: `log-slow-statements`, which denotes
  how many seconds a database query can take before the query is
  logged at WARN level.

  The default behavior for this setting is to log queries that take more than 10
  seconds.

* Add support for a --debug flag, and a debug-oriented startup script

  This commit adds support for a new command-line flag: --debug.  For
  now, this flag only affects logging: it forces a console logger and
  ensures that the log level is set to DEBUG. The option is also
  added to the main config hash so that it can potentially be used for
  other purposes in the future.

  This commit also adds a shell script, `puppetdb-foreground`, which
  can be used to launch the services from the command line. This
  script will be packaged (in /usr/sbin) along with the
  puppetdb-ssl-setup script, and may be useful in helping users
  troubleshoot problems on their systems (especially problems with
  daemon startup).

Notable fixes:

* Update CONTRIBUTING.md to better reflect reality

  The process previously described in CONTRIBUTING.md was largely
  vestigial; we've now updated that documentation to reflect the
  actual, current contribution process.

* Proper handling of composite namevars

  Normally, as part of converting a catalog to the PuppetDB wire
  format, we ensure that every resource has its namevar as one of its
  aliases. This allows us to handle edges that refer to said resource
  using its namevar instead of its title.

  However, Puppet implements `#namevar` for resources with composite
  namevars in a strange way, only returning part of the composite
  name. This can result in bugs in the generated catalog, where we
  may have 2 resources with the same alias (because `#namevar` returns
  the same thing for both of them).

  Because resources with composite namevars can't be referred to by
  anything other than their title when declaring relationships,
  there's no real point to adding their aliases in anyways. So now we
  don't bother.

* Fix deb packaging so that the puppetdb service is restarted during
  upgrades

  Prior to this commit, when you ran a debian package upgrade, the
  puppetdb service would be stopped but would not be restarted.

* (#1406) Add curl-based query examples to docs

  The repo now contains examples of querying PuppetDB via curl over
  both HTTP and HTTPS.

* Documentation on how to configure PuppetDB to work with "puppet apply"

  There are some extra steps necessary to get PuppetDB working
  properly with Puppet apply, and there are limitations
  thereafter. The repo now contains documentation around what those
  limitations are, and what additional configuration is necessary.

* Upgraded testing during acceptance test runs

  We now automatically test upgrades from the last published version
  of PuppetDB to the currently-under-test version.

* (#15281) Added postgres support to acceptance testing

  Our acceptance tests now regularly run against both the embedded
  database and PostgreSQL, automatically, on every commit.

* (#15378) Improved behavior of acceptance tests in single-node
  environment

  We have some acceptance tests that require multiple nodes in order
  to execute successfully (mostly around exporting / collecting
  resources). If you tried to run them in a single-node environment,
  they would give a weird ruby error about 'nil' not defining a
  certain method. Now, they will be skipped if you are running without
  more than one host in your acceptance test-bed.

* Spec tests now work against Puppet master branch

  We now regularly and automatically run PuppetDB spec tests against
  Puppet's master branch.

* Acceptance testing for RPM-based systems

  Previously we were running all of our acceptance tests solely
  against Debian systems. We now run them all, automatically upon each
  commit against RedHat machines as well.

* Added new `rake version` task

  Does what it says on the tin.
