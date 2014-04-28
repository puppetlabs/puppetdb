---
title: "PuppetDB 2.0 » Release Notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

1.6.3
-----

PuppetDB 2.0.3 is a bugfix release.

Notable improvements and fixes:

* (PDB-510) Add migration to fix sequence for catalog.id

  The sequence for catalog.id had not been incremented during insert for migration
  20 'differential-catalog-resources'.

  This meant that the catalog.id column would throw a constraint exception during
  insertion until the sequence had increased enough to be greater then the max id
  already used in the column.

  While this was only a temporary error, it does cause puppetdb to start to throw
  errors and potentially dropping some catalog updates until a certain number of
  catalogs had been attempted. In some cases catalogs had been queued up and
  retried successfully, other cases meant they were simply dropped into the DLQ.

  The fix is to reset the sequence to match the max id on the catalogs.id column.

* RHEL 7 beta packages are now included

* (PDB-343) Fedora 18 packages are no longer to be built

* (PDB-468) Update CLI tools to use correct JAVA_BIN and JAVA_ARGS

  Some tools such as `puppetdb import|export` and `puppetdb foreground` where not
  honouring the JAVA_BIN defined in /etc/default|sysconfig/puppetdb.

* (PDB-437) Reduce API source code and version inconsistencies

  The API code when split between different versions created an opportunity for
  inconsistencies to grow. For example, some versioning inside the code supported
  this file based way of abstracting versions, but other functions required
  version specific handling.

  This patch solidifies the version handling to ensure we reduce in regressions
  relating to different versions of the query API and different operator handling.

* Use v3 end-point for import and benchmark tools

* (PDB-80)(packaging) Fixup logic in defaults file for java on EL

* (PDB-463) Fix assertion error in /v1/resources

* (PDB-238) Some code reduction work related to simplifying future query API version removal

* (PDB-446) Start in on the merge of v2 and v3 test namespaces for testing

* (PDB-435) Travis no longer has bundler installed by default, now installing it explicitly

* (PDB-437) Clojure lint cleanups

* Change tutorial and curl documentation examples to use v3 API

* Added examples to documentation for latest-report? and file

1.6.2
-----

PuppetDB 2.0.2 is a bugfix release.

Notable improvements and fixes:


* Provided an early release RPM SPEC for RHEL 7, no automated builds yet.

* (PDB-377) - Fixed a Fedora RPM packaging issue preventing PuppetDB
  from starting by disabling JAR repacking

* (PDB-341) - Fixed a naming issue when using subqueries for resource
  metadata like file and line with v3 of the API

* (PDB-128) - Oracle Java 7 package support

  Add support for Oracle Java 7 packages on Debian. This
  means that users who have older Debian based distros but do not have
  native JDK 7 packages can build their own Oracle Java 7 package (see
  https://wiki.debian.org/JavaPackage) and we will pull it in as a
  dependency.
 
* (PDB-106) - Added an explicit log message upon a failed agent run
  (previously would fail with “undefined method `[]' for nil:NilClass”)

* Change the order that filters are applied for events

  When using the `distinct-resources` flag of an event query, the
  previous behavior was that we would do the filtering of the events
  *before* we would eliminate duplicate resources. This was not the
  expected behavior in many cases in the UI; for example, when filtering
  events based on event status, the desired behavior was to find all of
  the most recent events for each resource *first*, and then apply the
  filter to that set of resources. If we did the status filtering first,
  then we might end up in a state where we found the most recent
  'failed' event and showed it in the UI even if there were 'success'
  events on that resource afterwards.

  This commit changes the order that the filtering happens in.  We
  now do the `distinct` portion of the query before we do the filtering.

  However, in order to achieve reasonable performance, we need to
  at least include timestamp filtering in the `distinct` query; otherwise
  that portion of the query has to work against the entire table,
  and becomes prohibitively expensive.

  Since the existing timestamp filtering can be nested arbitrarily
  inside of the query (inside of boolean logic, etc.), it was not
  going to be possible to re-use that to handle the timestamp filtering
  for the `distinct` part of the query; thus, we had to introduce
  two new query parameters to go along with `distinct-resources`:
  `distinct-start-time` and `distinct-end-time`.  These are now
  required when using `distinct-resources`.

* (PDB-407) - Add Fedora 20 acceptance tests

* (PDB-425) - System V to SystemD upgrade

  Fixed issues upgrading from 1.5.2 to 1.6.2 on Fedora.
  1.5.2 used System V scripts while 1.6.x used SystemD which caused
  failures.

1.6.1
-----

Not released due to SystemD-related packaging issues on Fedora
  

1.6.0
-----

PuppetDB 2.0.0 is a performance and bugfix release.

Notable improvements and fixes:

* (PDB-81) Deprecate JDK6. It's been EOL for quite some time.

* (#21083) Differential fact storage

  Previously when facts for a node were replaced, all previous facts
  for that node were deleted and all new facts were inserted. Now
  existing facts are updated, old facts (no longer present) are
  deleted and new facts are inserted. This results in much less I/O,
  both because we have to write much less data and also because we
  reduce churn in the database tables, allowing them to stay compact
  and fast.

* (PDB-68) Differential edge storage

  Previously when a catalog wasn't detected as a duplicate, we'd have
  to reinsert all edges into the database using the new catalog's
  hash. This meant that even if 99% of the edges were the same, we'd
  still insert 100% of them anew and wait for our periodic GC to clean
  up the old rows. We now only modify the edges that have actually
  changed, and leave unchanged edges alone. This results in much less
  I/O as we touch substantially fewer rows.

* (PDB-69) Differential resource storage

  Previously when a catalog wasn't detected as a duplicate, we'd have
  to reinsert all resource metadata into the catalog_resources table
  using the catalog's new hash. Even if only 1 resource changed out of
  a possible 1000, we'd still insert 1000 new rows. We now only modify
  resources that have actually changed. This results in much less I/O
  in the common case.

* Streaming resource and fact queries. Previously, we'd load all rows
  from a resource or fact query into RAM, then do a bunch of sorting
  and aggregation to transform them into a format that clients
  expect. That has obvious problems involving RAM usage for large
  result sets. Furthermore, this does all the work for querying
  up-front...if a client disconnects, the query continues to tax the
  database until it completes. And lastly, we'd have to wait until all
  the query results have been paged into RAM before we could send
  anything to the client. New streaming support massively reduces RAM
  usage and time-to-first-result. Note that currently only resource
  and fact queries employ streaming, as they're the most
  frequently-used endpoints.

* Improvements to our deduplication algorithm. We've improved our
  deduplication algorithms to better detect when we've already stored
  the necessary data. Early reports from the field has show users who
  previously had deduplication rates in the 0-10% range jumping up to
  the 60-70% range. This has a massive impact on performance, as the
  fastest way to persist data is to already have it persisted!

* Eliminate joins for parameter retrieval. Much of the slowness of
  resource queries comes from the format of the resultset. We get back
  one row per parameter, and we need to collapse multiple rows into
  single resource objects that we can emit to the client. It's much
  faster and tidier to just have a separate table that contains a
  json-ified version of all a resource's parameters. That way, there's
  no need to collapse multiple rows at all, or do any kind of ORDER BY
  to ensure that the rows are properly sequenced.  In testing, this
  appears to speed up queries between 2x-3x...the improvement is much
  better the larger the resultset.

* (#22350) Support for dedicated, read-only databases. Postgres
  supports Hot Standby (http://wiki.postgresql.org/wiki/Hot_Standby)
  which uses one database for writes and another database for
  reads. PuppetDB can now point read-only queries at the hot standby,
  resulting in improved IO throughput for writes.

* (#22960) Don't automatically sort fact query results

  Previously, we'd sort fact query results by default, regardless of
  whether or not the user has requested sorting. That incurs a
  performance penalty, as the DB has to now to a costly sort
  operation. This patch removes the sort, and if users want sorted
  results they can use the new sorting parameters to ask for that
  explicitly.

* (#22947) Remove resource tags GIN index on Postgres. These indexes
  can get large and they aren't used. This should free up some
  precious disk space.

* (22977) Add a debugging option to help diagnose catalogs for a host
  that hash to different values

  Added a new global config parameter to allow debugging of catalogs
  that hash to a different value. This makes it easier for users to
  determine why their catalog duplication rates are low. More details
  are in the included "Troubleshooting Low Catalog Duplication" guide.

* (PDB-56) Gzip HTTP responses

  This patchset enables Jetty's gzip filter, which will automatically
  compress output with compressible mime-types (text, JSON, etc). This
  should reduce bandwidth requirements for clients who can handle
  compressed responses.

* (PDB-70) Add index on catalog_resources.exported

  This increases performance for exported resource collection
  queries. For postgresql the index is only a partial on exported =
  true, since indexing on the very common 'false' case is not that
  effective. This gives us a big perf boost with minimal disk usage.

* (PDB-85) Various fixes for report export and anonymization

* (PDB-119) Pin to RSA ciphers for all jdk's to work-around Centos 6
  EC failures

  We were seeing EC cipher failures for Centos 6 boxes, so we now pin
  the ciphers to RSA only for all JDK's to work-around the
  problem. The cipher suite is still customizable, so users can
  override this is they wish.

* Fixes to allow use of public/private key files generated by a wider
  variety of tools, such as FreeIPA.

* (#17555) Use systemd on recent Fedora and RHEL systems.

* Documentation for `store-usage` and `temp-usage` MQ configuration
  options.

* Travis-CI now automatically tests all pull requests against both
  PostgreSQL and HSQLDB. We also run our full acceptance test suite on
  incoming pull requests.

* (PDB-102) Implement Prismatic Schema for configuration validation

  In the past our configuration validation was fairly ad-hoc and imperative. By
  implementing an internal schema mechanism (using Prismatic Schema) this should
  provice a better and more declarative mechanism to validate users
  configuration rather then letting mis-configurations "fall through" to
  internal code throwing undecipherable Java exceptions.

  This implementation also handles configuration variable coercion and
  defaulting also, thus allowing us to remove a lot of the bespoke code we had
  before that performed this activity.

* (PDB-279) Sanitize report imports

  Previously we had a bug PDB-85 that caused our exports on 1.5.x to fail. This
  has been fixed, but alas people are trying to import those broken dumps into
  1.6.x and finding it doesn't work.

  This patch sanitizes our imports by only using select keys from the reports
  model and dropping everything else.

* (PDB-107) Support chained CA certificates

  This patch makes puppetdb load all of the certificates in the CA .pem
  file into the in-memory truststore. This allows users to use a
  certificate chain (typically represented as a sequence of certs in a
  single file) for trust.

1.5.2
-----

PuppetDB 1.5.2 is a maintenance and bugfix release.

Notable changes and fixes:

* Improve handling of logfile names in our packaging, so that it's easier to
  integrate with tools like logrotate.

* Better error logging when invoking subcommands.

* Fix bugs in `order-by` support for `facts`, `fact-names`, and `resources` query endpoints.

* Documentation improvements.

* Add packaging support for Ubuntu `saucy`.

* Add support for PEM private keys that are not generated by Puppet, and are not
  represented as key pairs.

* Fix inconsistencies in names of `:sourcefile` / `:sourceline` parameters when
  using `nodes/<node>/resources` version of `nodes` endpoint; these were always
  being returned in the `v2` response format, even when using `/v3/nodes`.

1.5.1
-----

NOTE: This version was not officially released, as additional fixes
came in between the time we tagged this and the time we were going to
publish release artifacts.

1.5.0
-----

PuppetDB 1.5.0 is a new feature release.  (For detailed information about
any of the API changes, please see the official documentation for PuppetDB 1.5.)

Notable features and improvements:

* (#21520) Configuration for soft failure when PuppetDB is unavailable

  This feature adds a new option 'soft_write_failure' to the puppetdb
  configuration.  If enabled the terminus behavior is changed so that if a
  command or write fails, instead of throwing an exception and causing the agent
  to stop it will simply log an error to the puppet master log.

* New v3 query API

  New `/v3` URLs are available for all query endpoints.  The `reports` and
  `events` endpoints, which were previously considered `experimental`, have
  been moved into `/v3`.  Most of the other endpoints are 100% backwards-compatible
  with `/v2`, but now offer additional functionality.  There are few minor
  backwards-incompatible changes, detailed in the comments about individual
  endpoints below.

* Query paging

  This feature adds a set of new HTTP query parameters that can be used with most
  of the query endpoints (`fact_names`, `facts`, `resources`, `nodes`, `events`,
  `reports`, `event-counts`) to allow paging through large result sets over
  multiple queries.  The available HTTP query parameters are:

     * `limit`: an integer specifying the maximum number of results to
        return.
     * `order-by`: a list of fields to sort by, in ascending or descending order.
        The legal set of fields varies by endpoint; see the documentation for
        individual endpoints for more info.
     * `offset`: an integer specifying the first result in the result set that
        should be returned.  This can be used in combination with `limit`
        and `order-by` to page through a result set over multiple queries.
     * `include-total`: a boolean flag which, if set, will cause the HTTP response
       to contain an `X-Records` header indicating the total number of results that are
       available that match the query.  (Mainly useful in combination with `limit`.)

* New features available on `events` endpoint

    * The `events` data now contains `file` and `line` fields.  These indicate
      the location in the manifests where the resource was declared.  They can
      be used as input to an `events` query.
    * Add new `configuration-version` field, which contains the value that Puppet
      supplied during the agent run.
    * New `containing-class` field: if the resource is declared inside of a
      Puppet class, this field will contain the name of that class.
    * New `containment-path` field: this field is an array showing the full
      path to the resource from the root of the catalog (contains an ordered
      list of names of the classes/types that the resource is contained within).
    * New queryable timestamp fields:
        * `run-start-time`: the time (on the agent node) that the run began
        * `run-end-time`: the time (on the agent node) that the run completed
        * `report-receive-time`: the time (on the puppetdb node) that the report was received by PuppetDB
    * Restrict results to only include events that occurred in the latest report
      for a given node: `["=", "latest-report?", true]`

* New `event-counts` endpoint

    `v3` of the query API contains a new `event-counts` endpoint, which can be
    used to retrieve count data for an event query.  The basic input to the
    endpoint is an event query, just as you'd provide to the `events` endpoint,
    but rather than returning the actual events, this endpoint returns counts
    of `successes`, `failures`, `skips`, and `noops` for the events that match
    the query.  The counts may be aggregated on a per-resource, per-class,
    or per-node basis.

* New `aggregate-event-counts` endpoint

  This endpoint is similar to the `event-counts` endpoint, but rather than
  aggregating the counts on a per-node, per-resource, or per-class basis,
  it returns aggregate counts across your entire population.

* New `server-time` endpoint

  This endpoint simply returns a timestamp indicating the current time on
  the PuppetDB server.  This can be used as input to time-based queries
  against timestamp fields that are populated by PuppetDB.

* Minor changes to `resources` endpoint for `v3`

  The `sourcefile` and `sourceline` fields have been renamed to `file` and `line`,
  for consistency with other parts of the API.

* Minor changes relating to reports storage and query

  * `store report` command has been bumped up to version `2`.
  * Report data now includes a new `transaction-uuid` field; this is generated
    by Puppet (as of Puppet 3.3) and can be used to definitively correlate a report
    with the catalog that was used for the run.  This field is queryable on the
    `reports` endpoint.
  * Reports now support querying by the field `hash`; this allows you to retrieve
    data about a given report based on the report hash for an event returned
    by the `events` endpoint.

* Minor changes relating to catalog storage

  * `store catalog` command has been bumped to version `3`.
  * Catalog data now includes the new `transaction-uuid` field; see notes above.

Bug fixes:

* PuppetDB report processor was truncating microseconds from report timestamps;
  all timestamp fields should now retain full precision.

* Record resource failures even if Puppet doesn't generate an event for them in the
  report: in rare cases, Puppet will generate a report that indicates a failure
  on a resource but doesn't actually provide a failure event.  Prior to PuppetDB
  1.5, the PuppetDB report processor was only checking for the existence of
  events, so these resources would not show up in the PuppetDB report.  This is
  really a bug in Puppet (which should be fixed as of Puppet 3.3), but the PuppetDB
  report processor is now smart enough to detect this case and synthesize a failure
  event for the resource, so that the failure is at least visible in the PuppetDB
  report data.

* Filter out the well-known "Skipped Schedule" events: in versions of Puppet prior
  to 3.3, every single agent report would include six events whose status was
  `skipped` and whose resource type was `Schedule`.  (The titles were `never`,
  `puppet`, `hourly`, `daily`, `weekly`, and `monthly`.)  These events were not
  generally useful and caused a great deal of pollution in the PuppetDB database.
  They are no longer generated as of Puppet 3.3, but for compatibility with
  older versions of Puppet, the report terminus in PuppetDB 1.5 will filter
  these events out before storing the report in PuppetDB.

* Log a message when a request is blocked due to the certificate whitelist:
  prior to 1.5, when a query or command was rejected due to PuppetDB's certificate
  whitelist configuration, there was no logging on the server that could be used
  to troubleshoot the cause of the rejection.  We now log a message, in hopes of
  making it easier for administrators to track down the cause of connectivity
  issues in this scenario.

* (#22122) Better log messages when puppetdb-ssl-setup is run before Puppet
  certificates are available.

* (#22159) Fix a bug relating to anonymizing catalog edges in exported PuppetDB
  data.

* (#22168) Add ability to configure maximum number of threads for Jetty (having too
  low of a value for this setting on systems with large numbers of cores could
  prevent Jetty from handling requests).

1.4.0
-----

PuppetDB 1.4.0 is a new feature release.

Notable features and improvements:

* (#21732) Allow SSL configuration based on Puppet PEM files (Chris Price & Ken Barber)

  This feature introduces some functions for reading keys and
  certificates from PEM files, and dynamically constructing java
  KeyStore instances in memory without requiring a .jks file on
  disk.

  It also introduces some new configuration options that may
  be specified in the `jetty` section of the PuppetDB config
  to initialize the web server SSL settings based on your
  Puppet PEM files.

  The tool `puppetdb-ssl-setup` has been modified now to handle these new
  parameters, but leave legacy configuration alone by default.

* (#20801) allow */* wildcard (Marc Fournier)

  This allows you to use the default "Accept: */*" header to retrieve JSON
  documents from PuppetDB without needed the extra "Accept: applicaiton/json"
  header when using tools such as curl.

* (#15369) Terminus for use with puppet apply (Ken Barber)

  This patch provides a new terminus that is suitable for facts storage usage
  with masterless or `puppet apply` usage. The idea is that it acts as a fact
  cache terminus, intercepting the first save request and storing the values
  in PuppetDB.

* Avoid Array#find in Puppet::Resource::Catalog::Puppetdb#find_resource (Aman Gupta)

  This patch provides performance improvements in the terminus, during the
  synthesize_edges stage. For example, in cases with 10,000 resource (with
  single relationships) we saw a reduction from 83 seconds to 6 seconds for a
  full Puppet run after this patch was applied.

* Portability fixes for OpenBSD (Jasper Lievisse Adriaanse)

  This series of patches from Jasper improved the scripts in PuppetDB so they
  are more portable to BSD based platforms like OpenBSD.

* Initial systemd service files (Niels Abspoel)

* Updated spec file for suse support (Niels Abspoel)

  This change wil make puppetdb rpm building possible on opensuse
  with the same spec file as redhat.

* (#21611) Allow rake commands to be ran on Ruby 2.0 (Ken Barber)

  This allows rake commands to be ran on Ruby 2.0, for building on Fedora 19
  to be made possible.

* Add puppetdb-anonymize tool (Ken Barber)

  This patch adds a new tool 'puppetdb-anonymize' which provides users with a
  way to anonymize/scrub their puppetdb export files so they can be shared
  with third parties.

* (#21321) Configurable SSL protocols (Deepak Giridharagopal)

  This patch adds an additional configuration option, `ssl-protocols`, to
  the `[jetty]` section of the configuration file. This lets users specify
  the exact list of SSL protocols they wish to support, such as in cases
  where they're running PuppetDB in an environment with strict standards
  for SSL usage.

  If the option is not supplied, we use the default set of protocols
  enabled by the local JVM.

* Create new conn-lifetime setting (Chuck Schweizer & Deepak Giridharagopal)

  This creates a new option called `conn-lifetime` that governs how long
  idle/active connections stick around.

* (#19174) Change query parameter to optional for facts & resources (Ken Barber)

  Previously for the /v2/facts and /v2/resources end-point we had documented that
  the query parameter was required, however a blank query parameter could be used
  to return _all_ data, so this assertion wasn't quite accurate. However one
  could never really drop the query parameter as it was considered mandatory and
  without it you would get an error.

  To align with the need to return all results at times, and the fact that
  making a query like '/v2/facts?query=' to do such a thing is wasteful, we have
  decided to drop the mandatory need for the 'query' parameter.

  This patch allows 'query' to be an optional parameter for /v2/facts & resources
  by removing the validation check and updating the documentation to reflect this
  this new behaviour.

  To reduce the risk of memory bloat, the settings `resource-query-limit` still
  apply, you should use this to set the maximum amount of resources in a single
  query to provide safety from such out of memory problems.

Bug fixes:

* Fix the -p option for puppetdb-export/import (Ken Barber)

* Capture request metrics on per-url/per-path basis (Deepak Giridharagopal)

  When we migrated to versioned urls, we didn't update our metrics
  middleware. Originally, we had urls like /resources, /commands, etc.
  We configured the metrics middlware to only take the first component of
  the path and create a metric for that, so we had metrics that tracked
  all requests to /resources, /commands, etc. and all was right with the
  world.

  When we moved to versioned urls, though, the first path component became
  /v1, /v2, etc. This fix now allows the user to provide full URL paths to
  query specific end-points, while still supporting the older mechanism of
  passing 'commands', 'resources',  and 'metrics'.

* (21450) JSON responses should be UTF-8 (Deepak Giridharagopal)

  JSON is UTF-8, therefore our responses should also be UTF-8.

Other important changes & refactors:

* Upgrade internal components, including clojure (Deepak Giridharagopal)

  - upgrade clojure to 1.5.1
  - upgrade to latest cheshire, nrepl, libs, tools.namespace, clj-time, jmx,
    ring, at-at, ring-mock, postgresql, log4j

* Change default db conn keepalive to 45m (Deepak Giridharagopal)

  This better matches up with the standard firewall or load balancer
  idle connection timeouts in the wild.

1.3.2
-----

PuppetDB 1.3.2 is a bugfix release.  Many thanks to the following
people who contributed patches to this release:

* Chris Price

Bug fixes:

* Size of column `puppet_version` in the database schema is insufficient

  There is a field in the database that is used to store a string
  representation of the puppet version along with each report.  Previously,
  this column could contain a maximum of 40 characters, but for
  certain builds of Puppet Enterprise, the version string could be
  longer than that.  This change simply increases the maximum length of
  the column.

1.3.1
-----

PuppetDB 1.3.1 is a bugfix release.  Many thanks to the following
people who contributed patches to this release:

* Chris Price
* Deepak Giridharagopal
* Ken Barber
* Matthaus Owens
* Nick Fagerlund

Bug fixes:

* (#19884) Intermittent SSL errors in Puppet master / PuppetDB communication

  There is a bug in OpenJDK 7 (starting in 1.7 update 6) whereby SSL
  communication using Diffie-Hellman ciphers will error out a small
  percentage of the time.  In 1.3.1, we've made the list of SSL ciphers
  that will be considered during SSL handshake configurable.  In addition,
  if you're using an affected version of the JDK and you don't specify
  a legal list of ciphers, we'll automatically default to a list that
  does not include the Diffie-Hellman variants.  When this issue is
  fixed in the JDK, we'll update the code to re-enable them on known
  good versions.

* (#20563) Out of Memory error on PuppetDB export

  Because the `puppetdb-export` tool used multiple threads to retrieve
  data from PuppetDB and a single thread to write the data to the
  export file, it was possible in certain hardware configurations to
  exhaust all of the memory available to the JVM.  We've moved this
  back to a single-threaded implementation for now, which may result
  in a minor performance decrease for exports, but will prevent
  the possibility of hitting an OOM error.

* Don't check for newer versions in the PE-PuppetDB dashboard

  When running PuppetDB as part of a Puppet Enterprise installation, the
  PuppetDB package should not be upgraded independently of Puppet Enterprise.
  Therefore, the notification message that would appear in the PuppetDB
  dashboard indicating that a newer version is available has been removed
  for PE environments.


1.3.0
-----

Many thanks to the following people who contributed patches to this
release:

* Branan Purvine-Riley
* Chris Price
* Deepak Giridharagopal
* Ken Barber
* Matthaus Owens
* Moses Mendoza
* Nick Fagerlund
* Nick Lewis

Notable features:

* Report queries

  The query endpoint `experimental/event` has been augmented to support a
  much more interesting set of queries against report data.  You can now query
  for events by status (e.g. `success`, `failure`, `noop`), timestamp ranges,
  resource types/titles/property name, etc.  This should make the report
  storage feature of PuppetDB *much* more valuable!

* Import/export of PuppetDB reports

  PuppetDB 1.2 added the command-line tools `puppetdb-export` and `puppetdb-import`,
  which are useful for migrating catalog data between PuppetDB databases or
  instances.  In PuppetDB 1.3, these tools now support importing
  and exporting report data in addition to catalog data.

Bug fixes:

* `puppetdb-ssl-setup` is now smarter about not overwriting keystore
  settings in `jetty.ini` during upgrades

* Add database index to `status` field for events to improve query performance

* Fix `telnet` protocol support for embedded nrepl

* Upgrade to newer version of nrepl

* Improvements to developer experience (remove dependency on `rake` for
  building/running clojure code)

1.2.0
-----

Many thanks to following people who contributed patches to this
release:

* Chris Price
* Deepak Giridharagopal
* Erik Dalén
* Jordi Boggiano
* Ken Barber
* Matthaus Owens
* Michael Hall
* Moses Mendoza
* Nick Fagerlund
* Nick Lewis

Notable features:

* Automatic node purging

  This is the first feature which allows data in PuppetDB to be deleted. The
  new `node-purge-ttl` setting specifies a period of time to keep deactivated
  nodes before deleting them. This can be used with the `puppet node
  deactivate` command or the automatic node deactivation `node-ttl` setting.
  This will also delete all facts, catalogs and reports for the purged nodes.
  As always, if new data is received for a deactivated node, the node will be
  reactivated, and thus exempt from purging until it is deactivated again. The
  `node-purge-ttl` setting defaults to 0, which disables purging.

* Import/export of PuppetDB data

  Two new commands have been added, `puppetdb-export` and `puppetdb-import`.
  These will respectively export and import the entire collection of catalogs
  in your PuppetDB database. This can be useful for migrating from HSQL to
  PostgreSQL, for instance.

  There is also a new Puppet subcommand, `puppet storeconfigs export`. This
  command will generate a similar export data from the ActiveRecord
  storeconfigs database. Specifically, this includes only exported resources,
  and is useful when first migrating to PuppetDB, in order to prevent failures
  due to temporarily missing exported resources.

* Automatic dead-letter office compression

  When commands fail irrecoverably or over a long period of time, they are
  written to disk in what is called the dead-letter office (or DLO). Until now,
  this directory had no automatic maintenance, and could rapidly grow in size.
  Now there is a `dlo-compression-threshold` setting, which defaults to 1 day,
  after which commands in the DLO will be compressed. There are also now
  metrics collected about DLO usage, several of which (size, number of
  messages, compression time) are visible from the PuppetDB dashboard.

* Package availability changes

  Packages are now provided for Fedora 18, but are no longer provided for
  Ubuntu 11.04 Natty Narwhal, which is end-of-life. Due to work being done to
  integrate PuppetDB with Puppet Enterprise, new pe-puppetdb packages are not
  available.

Bug fixes:

* KahaDB journal corruption workaround

  If the KahaDB journal, used by ActiveMQ (in turn used for asynchronous
  message processing), becomes corrupted, PuppetDB would fail to start.
  However, if the embedded ActiveMQ broker is restarted, it will cleanup the
  corruption itself. Now, PuppetDB will recover from such a failure and restart
  the broker automatically.

* Terminus files conflict between puppetdb-terminus and puppet

  There was a conflict between these two packages over ownership of certain
  directories which could cause the puppetdb-terminus package to fail to
  install in some cases. This has been resolved.

1.1.1
-----

PuppetDB 1.1.1 is a bugfix release.  It contains the following fixes:

* (#18934) Dashboard Inventory Service returns 404

  Version 1.1.0 of the PuppetDB terminus package contained a faulty URL for
  retrieving fact data for the inventory service.  This issue is fixed and
  we've added better testing to ensure that this doesn't break again in the
  future.

* (#18879) PuppetDB terminus 1.0.5 is incompatible with PuppetDB 1.1.0

  Version 1.1.0 of the PuppetDB server package contained some API changes that
  were not entirely backward-compatible with version 1.0.5 of the PuppetDB
  terminus; this caused failures for some users if they upgraded the server
  to 1.1.0 without simultaneously upgrading the terminus package.  Version 1.1.1
  of the server is backward-compatible with terminus 1.0.5, allowing an easier
  upgrade path for 1.0.x users.


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
