---
title: "PuppetDB 2.2 » Release Notes"
layout: default
canonical: "/puppetdb/latest/release_notes.html"
---

[configure_postgres]: ./configure.html#using-postgresql
[pg_trgm]: http://www.postgresql.org/docs/current/static/pgtrgm.html

2.3.0
-----

PuppetDB 2.3.0 is a backwards-compatible release that adds support for
Puppet 4.

### Upgrading

* For the best-possible performance and scaling capacity, we recommend
  PostgreSQL version 9.4 or newer with the [`pg_trgm`][pg_trgm]
  extension enabled, as explained [here][configure_postgres], and we
  have officially deprecated versions earlier than 9.2.  HSQLDB is
  only recommended for local development because it has a number of
  scaling and operational issues.

* Make sure that all of your PuppetDB instances are shut down, and
  only upgrade one at a time.

* Make sure to upgrade your puppetdb-terminus package (on the host
  where your Puppet Master lives), and restart your master service.

### Contributors

Andrew Roetker, Erik Dalén, Ken Barber, Preben Ingvaldsen, Rob Braden,
Rob Nelson, Roger Ignazio, Ryan Senior, Wyatt Alt, and Jean Bond

### Changes

#### New Features

* PuppetDB now supports Puppet 4
  ([RE-3879](https://tickets.puppetlabs.com/browse/RE-3879))

* To support Puppet 2.7.x - 3.7.x. PuppetDB now handles hyphenated
  classnames
  ([PDB-1024](https://tickets.puppetlabs.com/browse/PDB-1024))

#### Bug Fixes and Maintenance

* NULL environment_ids should no longer prevent garbage collection.
  ([PDB-1076](https://tickets.puppetlabs.com/browse/PDB-1076))

    If a factset, report, or catalog is migrated from an earlier
    PuppetDB version or is submitted via an earlier puppetdb-terminus
    version, it will have NULL for the environment_id.  Previously
    that could prevent PuppetDB from cleaning up any environments at
    all.  The fix should also improve collection performance.

* The puppet-env script should no longer contain invalid code.
  Previously commands could end up on the same line, preventing (for
  example) environment variables from being set
  correctly. ([PDB-1212](https://tickets.puppetlabs.com/browse/PDB-1212))

* PuppetDB now uses the new Puppet Profiler API.
  ([PUP-3512](https://tickets.puppetlabs.com/browse/PUP-3512))

* `Profiler.profile()` should now be called with the correct arguments
  across all supported versions.
  ([PDB-1029](https://tickets.puppetlabs.com/browse/PDB-1029))

* The factset endpoint should no longer accept some unused and
  unintentionally accepted arguments.

#### Documentation

* The documentation for the `<=` and `>=`
  [operators](./api/query/v2/operators) has been fixed (the
  descriptions were incorrectly reversed).

* The firewall and SELinux requirements have been documented
  [here](./connect_puppet_master).
  ([PDB-137](https://tickets.puppetlabs.com/browse/PDB-137))

* Broken links have been fixed in the
  [connection](./connect_puppet_master) and [commands](./api/commands)
  documentation.

* A missing `-L` option has been added to a curl invocation
  [here](./install_from_souce).

* An incorrect reference to "Java" has been changed to "JVM" in the
  [configuration](./configure) documentation.

* Some minor edits have been made to the
  [fact-contents](./api/query/v4/fact-contents),
  [connection](./connect_puppet_master), and
  [KahaDB Corruption](./trouble_kahadb_corruption) documentation.

#### Testing

* The tests have been adjusted to accommodate Puppet 4.
  ([PDB-1052](https://tickets.puppetlabs.com/browse/PDB-1052)
  [PDB-995](https://tickets.puppetlabs.com/browse/PDB-995)
  [68bf176e0bd4d51c1ba3](https://github.com/puppetlabs/puppetdb/commit/68bf176e0bd4d51c1ba36909f4966671379a775e)
  [9ef68b635d1bb1f5338b](https://github.com/puppetlabs/puppetdb/commit/9ef68b635d1bb1f5338b3e40034a2ed787f2e107))

* The memory limit has been raised to 4GB for Travis CI tests.
  ([PDB-1202](https://tickets.puppetlabs.com/browse/PDB-1202))

* For now, the Fedora acceptance tests pin Puppet to 3.7.3.
  ([PDB-1200](https://tickets.puppetlabs.com/browse/PDB-1200))

* The Puppet version used by the spec tests can now be specified.
  ([PDB-1012](https://tickets.puppetlabs.com/browse/PDB-1012))

    The desired puppet version can be selected by setting the
    puppet_branch environment variable.  Values of `latest` and
    `oldest` will select the latest and oldest supported versions of
    puppet respectively.

* The bundler `--retry` argument is now used during acceptance
  testing.

* Retriable has been pinned to version `~> 1.4` to avoid Ruby 1.9.x
  incompatible versions.

* The tree-generator should be less likely to generate inappropriate test data.
  ([PDB-1109](https://tickets.puppetlabs.com/browse/PDB-1109))

* The source of the Leiningen command has been updated.
  ([OPS-5175](https://tickets.puppetlabs.com/browse/OPS-5175))

* The ec2 acceptance test template's el-5 image has been updated.
  ([7cd01ac051c719c6c768](https://github.com/puppetlabs/puppetdb/commit/68bf176e0bd4d51c1ba36909f4966671379a775e))

* Some v4 node test failures should no longer be hidden.
  ([PDB-1017](https://tickets.puppetlabs.com/browse/PDB-1017))

* Pull request testing now invokes a script from the repository
  instead of the Jenkins job.
  ([PDB-1034](https://tickets.puppetlabs.com/browse/PDB-1034))

* The Gemfile pins i18n to `~> 0.6.11` for Ruby 1.8.7 to prevent
  activesupport from pulling in a version of i18n that isn't
  compatible with Ruby 1.8.7.

* The acceptance tests now use Virtual Private Cloud (VPC) hosts.

* The Beaker AWS department (`BEAKER_department`) is now set to
  `eso-dept` for the acceptance tests to improve AWS usage reporting.

2.2.2
-----

PuppetDB 2.2.2 is a backwards-compatible security release to update our default
ssl settings and tests in response to the POODLE SSLv3 vulnerability disclosed 10/14/2014.

(see http://web.nvd.nist.gov/view/vuln/detail?vulnId=CVE-2014-3566)

### Before Upgrading

* For best-possible performance and scaling capacity, we recommend using the latest version of PostgreSQL (9.3 or higher).
We have officially deprecated PostgreSQL 9.1 and below. If you are using HSQLDB for production,
we also recommended switching to PostgreSQL at least 9.3, as HSQLDB has a number of scaling
and operational issues and is only recommended for testing and proof of concept installations.

* For PostgreSQL 9.3 we advise that users install the PostgreSQL extension `pg_trgm` for increased
indexing performance for regular expression queries. Using the command `create extension pg_trgm`
as PostgreSQL super-user and before starting PuppetDB will allow these new indexes to be created.

* Ensure during a package upgrade that you analyze any changed configuration files. For Debian
you will receive warnings when upgrading interactively about these files, and for RedHat based
distributions you will find that the RPM drops .rpmnew files that you should diff and ensure
that any new content is merged into your existing configuration.

* Make sure all your PuppetDB instances are shut down and only upgrade one at a time.

* As usual, don't forget to also upgrade your puppetdb-terminus package
(on the host where your Puppet Master lives), and restart your
master service.

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

### Contributors

Ken Barber, Ryan Senior

#### Security
* [PDB-962](https://tickets.puppetlabs.com/browse/PDB-962)

    This commit changes the default ssl protocol in the Jetty config from SSLv3 to TLSv1.
    If the user has specified SSLv3, this is allowed, but the user will be warned.

#### Testing
* [PDB-964](https://tickets.puppetlabs.com/browse/PDB-964)

    Change tests to use TLSv1 to avoid dependency issues on sites dropping TLSv1.

* [PDB-952](https://tickets.puppetlabs.com/browse/PDB-952)

     Add acceptance tests for CentOS 7

#### Documentation

* Update docs to include --tlsv1 in all https curl examples

2.2.1
-----

PuppetDB 2.2.1 consists of bug fixes and documentation updates and is
backwards compatible with PuppetDB 2.2.0.

### Before Upgrading

* For best-possible performance and scaling capacity, we recommend using the latest version of PostgreSQL (9.3 or higher).
We have officially deprecated PostgreSQL 9.1 and below. If you are using HSQLDB for production,
we also recommended switching to PostgreSQL at least 9.3, as HSQLDB has a number of scaling
and operational issues and is only recommended for testing and proof of concept installations.

* For PostgreSQL 9.3 we advise that users install the PostgreSQL extension `pg_trgm` for increased
indexing performance for regular expression queries. Using the command `create extension pg_trgm`
as PostgreSQL super-user and before starting PuppetDB will allow these new indexes to be created.

* Ensure during a package upgrade that you analyze any changed configuration files. For Debian
you will receive warnings when upgrading interactively about these files, and for RedHat based
distributions you will find that the RPM drops .rpmnew files that you should diff and ensure
that any new content is merged into your existing configuration.

* Make sure all your PuppetDB instances are shut down and only upgrade one at a time.

* As usual, don't forget to also upgrade your puppetdb-terminus package
(on the host where your Puppet Master lives), and restart your
master service.

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

### Contributors

Justin Holguin, Ken Barber, Kylo Ginsberg, Russell Sim, Ryan Senior and Wyatt Alt.

### Database and Performance

* [PDB-900](https://tickets.puppetlabs.com/browse/PDB-900) Make performance improvements to fact values GC process

    Switched from a background thread to cleanup orphaned fact_values to one
    that deletes as it goes. Switching to deferred constraints also
    significantly improved performance on PostgreSQL. Deferred constraints are
    not available in HSQLDB, but since HSQLDB is unlikely to be running at the
    scale that will surface the issue, simply switching to delete-as-you-go
    should be sufficient to mitigate the issue to the extent that HSQL
    installations are affected.

### Bug Fixes and Maintenance

* [PDB-847](https://tickets.puppetlabs.com/browse/PDB-847) remove GlobalCheck for PE compatibility

    This patch deletes the GlobalCheck method that ran before each call to
    the PDB indirectors to check that the running version of Puppet was greater
    than 3.5. The check is redundant because Puppet 3.5 is a requirement for
    puppetdb-terminus and was causing a bug by mis-parsing PE semvers.

* [PDB-707](https://tickets.puppetlabs.com/browse/PDB-707) The first request to PuppetDB after DB backend restart fails

    This patch adds retry handling for 57P01 failures, which result in an
    PSQLException with a status code of 08003. The patch has been made
    forward compatible by catching all SQLExceptions with this status code.

    Version 0.8.0-RELEASE of Bonecp does not throw the correct sql state code
    today, however a patch has been raised to make this happen so we can
    upgrade to this version in the future.

* [PDB-653](https://tickets.puppetlabs.com/browse/PDB-653) DLO metrics don't update on PDB startup

    Previously, the DLO metrics only updated on a failed command submission, which
    meant restarting PDB would blank the metrics until the next failure. This patch
    initializes DLO metrics on PDB startup if the DLO directory, which is created
    on first failure, already exists.

* [PDB-865](https://tickets.puppetlabs.com/browse/PDB-865) PDB 2.2 migration fails when nodes have no facts

    This patch handles an error that occurs if a user attempts the 2.2 upgrade
    with nodes that have no associated facts in the database, and adds some
    additional testing around the migration.

* [PDB-868](https://tickets.puppetlabs.com/browse/PDB-868) Add exponential backoffs for db retry logic

    This patch increases the number of retries and adds exponential backoff
    logic to avoid the case where the database is still throwing errors for a short
    period of time. This commonly occurs during database restarts.

* [PDB-905](https://tickets.puppetlabs.com/browse/PDB-905) Fix containment-path for skipped events

    We had removed some < v4 reporting code as part of the last release, but I had
    also accidentally removed containment-path for skipped events. This was an
    effort to drop older Puppet support basically.

    This patch fixes that problem, and fixes the tests.

    I've also cleaned up a couple of other typos and style items, plus
    removed one more case of < v4 report format checking.

* [PDB-904](https://tickets.puppetlabs.com/browse/PDB-904) Switch fact_values.value_string trgm index to be GIN not GIST 

    There were reports of crashing due to a bug in pg_trgm indexing when a
    large fact value was loaded into PuppetDB.

    This patch removes the old index via migration, and will then replace it
    with the index handling mechanism.

* Add thread names to logging

    This patch adds thread names to the various logback.xml configuration files
    in this project. This provides us with better traceability when attempting
    to understand where a log message came from.

### Documentation

* [DOCUMENT-18](https://tickets.puppetlabs.com/browse/DOCUMENT-18) Mention DLO cleanup in docs

* [PDB-347](https://tickets.puppetlabs.com/browse/PDB-347) Add docs for new CRL and cert-chain TK features 

* Updated contributor link to https://cla.puppetlabs.com/

### Testing

* [PDB-13](https://tickets.puppetlabs.com/browse/PDB-13) Add acceptance test for Debian Wheezy

    This patch adds Debian Wheezy to our acceptance tests.

* Allow beaker rake task to accept preserve-hosts options

    In the past it just allowed true/false, but the current beaker accepts a series
    of options, including 'onfail'. This change passes through the value specified
    in the environment variable BEAKER_PRESERVE_HOSTS.

* Remove deprecated open_postgres_port option from puppetdb helper class

2.2.0
-----

This release was primarily focused on providing structured facts support for PuppetDB. Structured facts
allow a user to include hashes and arrays in their fact data, but also it provides the availability of
proper typing to include the storage of integers, floats, booleans as well as strings.

This release introduces the ability to store structured facts in PuppetDB, and use some new enhanced API's
to search and retrieve that data also.

With this change we have introduced the capability to store and retrieve trusted facts, which
are stored and retrieved in the same way as structured facts.

### Before Upgrading

* It is recommended for greater scaling capabilities and performance, to use the latest version of PostgreSQL (9.3 or higher).
We have officially deprecated PostgreSQL 9.1 and below. If you are using HSQLDB for production,
it is also recommended that you switch to PostgreSQL at least 9.3, as HSQLDB has a number of scaling
and operational issues, and is only recommended for testing and proof of concept installations.

* For PostgreSQL 9.3 you are advised to install the PostgreSQL extension `pg_trgm` for increased
indexing performance for regular expression queries. Using the command `create extension pg_trgm`
as PostgreSQL super-user and before starting PuppetDB will allow these new indexes to be created.

* Ensure during a package upgrade that you analyze any changed configuration files. For Debian
you will receive warnings when upgrading interactively about these files, and for RedHat based
distributions you will find that the RPM drops .rpmnew files that you should diff and ensure
that any new content is merged into your existing configuration.

* Make sure all your PuppetDB instances are shut down and only upgrade one at a time.

* As usual, don't forget to upgrade your puppetdb-terminus package
also (on the host where your Puppet Master lives), and restart your
master service.

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 then you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs, and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

### Contributors

Brian Cain, Eric Timmerman, Justin Holguin, Ken Barber, Nick Fagerlund, Ryan Senior and Wyatt Alt.

### New Features

#### New Endpoints

* [/v4/fact-contents](./api/query/v4/fact-contents.html)

    This end-point provides a new view on fact data, with structure in mind. It provides a way to
    traverse a structured facts paths and their values to search for and retrieve data contained deep within hashes,
    arrays and combinations there-of.

* [/v4/fact-paths](./api/query/v4/fact-paths.html)

    To aid with client application autocompletion and ahead-of-time fact schema layout this endpoint
    will provide the client with a full list of potential fact paths and their value types. This data
    is primarily used by user agents that wish to perhaps confine input via a form, or provide some
    level of autocompletion advice for a user typing a fact search.

* [/v4/factsets](./api/query/v4/factsets.html)

    This endpoint has been created to facilitate retrieval of factsets submitted
    for a node. With structured facts support, this includes preserving the type of the fact
    which may include a hash, array, real, integer, boolean or string.

    We have now switched to using this endpoint for the purposes of `puppetdb export`.
    This allows us to grab a whole node factsets in one go, in the past we would have to reassemble multiple top
    level facts to achieve the same results.

#### Changes to Endpoints

* [/v4/facts](./api/query/v4/facts)

    This endpoint is now capable of returning structured facts. Facts that contain hashes, arrays, floats, integers,
    booleans, strings (and combinations thereof) will be preserved when stored and able to now be returned via this
    endpoint.

* [/v3/facts](./api/query/v3/facts)

    This endpoint will return JSON stringified structured facts to preserve backwards compatibility.

#### Operators

* [`in` and `extract` (version 4)](./api/query/v4/operators.html#in)

    We have modified the v4 IN and EXTRACT operators to accept multiple fields at once.
    This allows the following...

        ["and" ["in", "name",
                 ["extract", "name", [select-fact-contents ["=","value",10]]]]
               ["in", "certname",
                 ["extract", "certname", [select-fact-contents ["=", "value", 10]]]]]

    to be re-written as

        ["in", ["name","certname"],
          ["extract", ["name", "certname"], ["select-fact-contents", ["=", "value", 10]]]]

    This was made to allow users to combine the `fact-contents` endpoint with the `facts` endpoint to combine the power
    of hierachical searching and aggregate results.

* [`~>` (version 4)](./api/query/v4/operators.html#regexp-array-match)

    This new operator was designed for the `path` field type to allow for matching a path
    against an array of regular expressions. The only endpoints that contains such fields today
    are [/v4/fact-contents](./api/query/v4/fact-contents.html) and [/v4/fact-paths](./api/query/v4/fact-paths).

#### Commands

In preparation for some future work, we have provided the ability to pass a `producer-timestamp` field via the `replace facts` and `replace catalogs` commands.
For `replace facts` we have also added the ability to pass any JSON object as the keys to the `value` field.

Due to these two changes, we have cut new versions of these commands for this release:

* [`replace catalog` version 5](./api/wire_format/catalog_format_v5.html)
* [`replace facts` version 3](./api/wire_format/facts_format_v3.html)

The older versions of the commands are immediately deprecated.

#### Terminus

The terminus changes for structured facts are quite simple, but significant:

* We no longer convert structured data to strings before submission to PuppetDB.
* Trusted facts have now been merged into facts in our terminus under the key `trusted`.

This means that for structured and trusted fact support all you have to do is enable them in Puppet,
and PuppetDB will start storing them immediately.

#### Database and Performance

* As part of the work for PDB-763 we have added a new facility to nag users to install the `pg_trgm` extension so we
  can utilize this index type for regular expression querying. This work is new, and only works on PostgreSQL 9.3 or higher.

* We have added two new garbage collection SQL cleanup queries to remove stale entries for structured facts.

#### Import/Export/Anonymization

All these tools have been modified to support structured facts. `export` specifically uses the new `/v4/factsets` endpoint now.

### Deprecations and Retirements

* We have now deprecated PostgreSQL 9.1 and older
* We no longer produce packages for Ubuntu 13.10 (it went EOL on July 17 2014)

### Bug Fixes and Maintenance

* [PDB-762](https://tickets.puppetlabs.com/browse/PDB-762) Fix broken export

    This pull request fixes a malformed post-assertion in the events-for-report-hash
    function that caused exports to fail on unchanged reports. Inserting proper
    parentheses made it apparent that a seq, rather than a vector, should be the
    expected return type.

* [PDB-826](https://tickets.puppetlabs.com/browse/PDB-826) Fix pathing for puppetdb-legacy

    For PE the puppetdb-legacy scripts (such as puppetdb-ssl-setup) expect the
    puppetdb script to be in the path. This assumption isn't always true, as we
    install PE in a weird place.

    This patch adjusts the PATH for this single exec so that it also includes
    the path of the directory where the original script is found, which is usually
    /opt/puppet/sbin.(PDB-826) Fix pathing for puppetdb-legacy

* [PDB-764](https://tickets.puppetlabs.com/browse/PDB-764) Malformed post conditions in query.clj.

    This fixes some trivial typos in query.clj that had previously caused some
    post-conditions to go unenforced.

### Documentation

* [DOCUMENT-97](https://tickets.puppetlabs.com/browse/DOCUMENT-97) Mention updating puppetdb module

    Upgrading PuppetDB using the module is pretty easy, but we should point
    out that the module should be updated *first*.

* [PDB-550](https://tickets.puppetlabs.com/browse/PDB-550) Update PuppetDB docs to include info on [LibPQFactory](./postgres_ssl.html)

    We have now updated our PostgreSQL SSL connectivity to documentation to include
    details on how to use the LibPQFactory methodology. This hopefully will alleviate
    the need to modify your global JVM JKS when using Puppet and self-signed certificates.

* Fix example for nodes endpoint to show 'certname' in response

* Revise API docs for updated info, clarity, consistency, and formatting

    This revision touches most of the pages in the v3 and v4 API docs, as well as
    the release notes. We've:

    * Standardized some squirmy terminology
    * Adjusted the flow of several pages
    * Caught two or three spots where the docs lagged behind the implementation
    * Made the Markdown syntax a little more portable (summary: Let's not use the
      "\n: " definition list syntax anymore. Multi-graf list items and nested lists
      get indented four spaces, not two or three.)
    * Added context about how certain objects work and how they relate to other objects
    * Added info about how query operators interact with field data types

* Change some old URLs, remove mentions of inventory service.

    Some of these files have moved, and others should point to the latest version
    instead of a specific version.

    And the inventory service is not really good news anymore. People should just
    use puppetdb's api directly.

### Testing

* [PDB-822](https://tickets.puppetlabs.com/browse/PDB-822) Change acceptance tests to incorporate structured facts.

    This patch augments our basic_fact_retrieval and import_export acceptance tests
    to include structured facts, and also bumps the version of the nodes query in
    the facts/find indirector so structured facts are returned when puppet facts
    find is issued to the PDB terminus.

* Split out acceptance and unit test gems in a better way

    We want to avoid installing all of the unit test gems when running acceptance.
    This patch moves rake into its own place so we can use `--without test` with bundler properly.

* Switch confine for basic test during acc dependency installation

    The way we were using confine was wrong, and since this is now more strict
    in beaker it was throwing errors in the master smoke tests. This patch
    just replaces it for a basic include? on the master platform instead.

* Fix old acceptance test refspec issue

    The old refspec for acceptance testing source code only really worked for the
    PR testing workflow. This patch makes it work for the command line or polling
    based workflow as well.

    Without it, it makes it hard to run beaker acceptance tests from the command
    line.

2.1.0
-----

PuppetDB 2.1.0 is a feature release focusing on new query
capabilities, streaming JSON support on all endpoints and a new report
status field for determining if a Puppet run has failed. Note that
this release is backward compatible with 2.0.0, but users must upgrade
PuppetDB terminus to 2.1.0 when upgrading the PuppetDB instance to
2.1.0.

Things to take note of before upgrading:

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 then you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs, and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

* Make sure all your PuppetDB instances are shut down and only upgrade
one at a time.

* As usual, don't forget to upgrade your puppetdb-terminus package
also (on the host where your Puppet Master lives), and restart your
master service.

New Features:

* (PDB-660) Switch all query endpoints to stream JSON results

    The following endpoints have been switched over to streaming:

    - event-counts
    - reports
    - nodes
    - environments
    - events

    Using 'event-query-limit' is now deprecated, use the normal
    paging/streaming functionality to achieve the same results.

* (PDB-658, PDB-697) Implement new "query engine" for v4

    This rewrite of the v4 API query infrastructure unifies query
    operators across all endpoints. Each endpoint now supports all
    operators appropriate for the given field of that type. As an
    example, any string field can now be searched by regular expression.
    All dates can be search with inequality operators like < or > for
    searching via date ranges. There are also many new queryable fields.
    Below summarizes the new features of the switch to this query engine

    events endpoint
     - Added configuration-version as a queryable field
     - Added containment-path as a queryable field (queryable in a way similar to tags)

    nodes endpoint
     - Added facts-timestamp, catalog-timestamp, report-timestamp    as a queryable field

    reports endpoint
     - Added puppet-version, report-format, configuration-version, start-time,
         end-time, receive-time, transaction-uuid as queryable fields

    null? operator
     - new operator that checks for the presence or absence of a value

    Some endpoints previously returned NULL values when using a "not"
    query such as ["not", ["=", "line", 10]]. The query engine follows
    SQL semantics, so if you want NULL values, you should explicty ask
    for it like:

    ["or",
        ["not", ["=", "line", 10]]
        ["null?", "line" true]]

* (PDB-162) Add regexp support to resource parameter queries

    The query engine supported this, but the existing "rewrite" rule, to go
    from the shorthand parameter syntax to the nested resource query didn't
    recognize ~. That is fixed with this commit, so regexps will now work on parameters.

* (PDB-601) Do not require query operator on reports endpoint

    With this pull request, hitting the reports endpoint without a query argument
    will return the full reports collection.    This behavior is consistent with
    that of the nodes, facts, and resources endpoints.

* (PDB-651) Allow the web app URL prefix to be configurable

    Previously PuppetDB always used the context root "/", meaning all
    queries etc would be something like
    "http://localhost:8080/v4/version". This change allows users to
    specify a different context root, like
    "http://localhost:8080/my-context-root/v4/version". See the
    url-prefix configuration documentation for more info

* (PDB-16) Add status to stored reports

    Previously there was no way to distinguish between failed puppet runs
    and successful puppet runs as we didn't store report status. This commit
    adds support for report status to the "store report" command, v4 query
    API and model.

* (PDB-700) Allow changes to maxFrameSize in activemq

    maxFrameSize previously defaulted to 100 MB.    Now default is 200 MB with user
    configurability.

Bug Fixes and Maintenance:

* (PDB-675) Fix Debian/Ubuntu PID missing issue

    In the past in Debian and Ubuntu releases we had issues where the
    PuppetDB system V init scripts were not stopping the PuppetDB
    process whenever a PID file was missing. This patch now introduces a
    fallback that will kill any java process running as the puppetdb
    user, if the PID file is missing.

* (PDB-551) Created a versioning policy document

    This document let's consumers of the PuppetDB API know what to
    expect from an API perspective when new versions of PuppetDB are
    release. This document is a separate page called "Versioning Policy"
    and is included in our API docs

* (PDB-164) Add documentation for select-nodes subquery operator

    This pull request supplies V4 API documentation for the select-nodes subquery
    operator, which was previously supported but undocumented.

* (PDB-720) Fix services test with hard coded Jetty port

    Fixed this issue by moving code that dynamically picks a free port out
    of import-export-roundtrip and into a separate ns. I just switched the
    services test to use that code and there should no longer be conflicts.

* (RE-1497) Remove quantal from build_defaults

    This commit removes quantal from all build defaults because it is end of
    life. It removes the defaults from the build_defaults yaml.

* (PDB-240) Replace anonymize.clj read-string with clojure.edn/read-string

    This patch replaces a call to read-string in anonymize.clj with a call to
    clojure.edn/read-string. Unlike clojure.core/read-string,
    clojure.edn/read-string is safe to use with untrusted data and guaranteed to
    be free of side-effects.

* (PDB-220) Coerce numerical function output in manifests to string

    Previously, when a user defined a numeric-valued function in a puppet manifest
    and submitted it to notify, the resource-title would remain numeric and
    PuppetDB would throw exceptions while storing reports. Per the docs,
    resource-title must be a string. This pull request avoids the problem by
    coercing resource-title to string.

* (PDB-337) Remove extraneous _timestamp fact

    Previously a _timestamp fact was submitted to puppetDB even though _timestamp
    was originally intended for internal use.    This commit strips internal data
    (all preceded by "_") from the factset before submission to PuppetDB.

* (PDB-130) Fixes a nasty traceback exposed when users run import from command line with an invalid filename. A friendly message is now printed instead.

* (PDB-577) Lower KahaDB MessageDatabase logging threshold.

    Previously, premature termination of PuppetDB import under specific ownership
    conditions led to a residual KahaDB lock file that could prevent subsequent imports
    from running with no obvious reason why.    This patch lowers the log threshold
    for KahaDB MessageDataBase so affected users are informed.

* (PDB-686) Add warning about PDB-686 to release notes

    This adds a warning about PDB-686 to the release notes so users know how to
    work-around it.

    This also cleans up the linking in our current release notes, and removes the
    warning about Puppet 3.4.x, because we pin against 3.5.1 and greater anyway.

* Add sbin_dir logic to Rakefile for Arch linux

* (PDB-467) Merge versioning tests for http testing into non-versioned files

    This patch removes all remaining versioned http test files into shared
    unversioned files, so that we may start iterating across versions in the
    same file.

* Fix comparison in dup resources acceptance test

    Due to changes in Puppet 3.6.0, the comparison done in our resource duplication
    tests no longer matches the actual output. This patch ammends the comparison to
    match Puppet 3.6.0 output now.

* (PDB-597) Add trusty build default

    This includes trusty (Ubuntu 14.04) in our builds.

* Unpin the version of beaker

    We had pinned beaker previously because we were waiting for some of our new EC2
    customisations to be merged in and released. This has been done now.

* Fix a race condition in the import/export round-trip clojure tests

    This scenario occurred if command processing for facts is slow. A result
    with the hard coded certname would be returned with no fact values or
    environment. This commit fixes the code to only return results when
    facts are found.

* (PDB-309) Update config conversion code for Schema 0.2.1

    Much of the code for converting user provided config to the internal types (i.e.
    "10" to Joda time 10 Seconds etc) is no longer necessary with the new
    coerce features of Schema. This commit switches to the new version and
    makes the necessary changes to use the coerce feature.

2.0.0
-----

PuppetDB 2.0.0 is a feature release focusing on environments support.
Note that this is a major version bump and there are several breaking
changes, including dropping support for versions of PostgreSQL prior to
version 8.4 and Java 1.6. See the "Deprecations and potentially
breaking changes" section below for more information.

Things to take note of before upgrading:

* If you receive the error "Could not open
/etc/puppet/log4j.properties" or "Problem parsing XML document",
this is because we have changed the packaged config.ini to point at a new logging configuration file:
logback.xml. However during package installation some package managers
will cowardly refuse to just update config.ini, this in particular
affects RPM. After upgrading you should ensure any .rpmnew files are
reviewed and that changes to our vendored version are now merged with
your version of config.ini on disk. See
[PDB-656](https://tickets.puppetlabs.com/browse/PDB-656) for more details.

* If you are running Ubuntu 12.04 and Ruby 1.9.3-p0 then you may find
that you will sometimes receive the error "idle timeout expired" in your
Puppet agent/master logs, and your PuppetDB logs. This is due to a bug
in that version of Ruby in particular. See
[PDB-686](https://tickets.puppetlabs.com/browse/PDB-686) for more details.

* Make sure all your PuppetDB instances are shut down and only upgrade
one at a time.

* As usual, don’t forget to upgrade your puppetdb-terminus package
also (on the host where your Puppet Master lives), and restart your
master service.

New features:

* (PDB-452,453,454,456,457,526,557) Adding support for storing, querying and importing/exporting environments

    This change forced a new revision of the `replace facts`,
    `replace catalog` and `store report` commands. The PuppetDB terminus
    also needed to be updated to support new environment information
    being sent to PuppetDB. USERS MUST ALSO UPDATE THE PUPPETDB
    TERMINUS. Previous versions of those commands (and wire formats) are
    now deprecated. See the PuppetDB API docs for more information.
    Environments support has been added to the new v4 (currently
    experimental) query API. The following query endpoints now include
    environment in the response:

    - facts
    - resources
    - nodes
    - catalogs
    - reports
    - events
    - event counts
    - aggregate event counts

    The below query endpoints now allow query/filtering by environment:

    - facts
    - resources
    - nodes
    - catalogs
    - reports
    - events

    This release also includes a new environments query endpoint to list
    all known environments and allow an easy filtering based on
    environment for things like events, facts reports and resources. See
    the query API docs for more information. PuppetDB
    import/export/anonymization and benchmark tool also now have
    environment support. Storeconfigs export does not include
    environments as that information is not being stored in the old
    storeconfigs module. Environments that are no longer associated with
    a fact set, report or catalog will be "garbage collected" by being
    removed from the database.

* (PDB-581) Add subqueries to events query endpoint

    The events endpoint now supports select-resources and select-facts

* (PDB-234) Add v4 query API, deprecate v2 query API

    This patch adds the new code relevant for doing any future v4 work. It has been
    raised as an experimental end-point only so there are no commitments to its
    interface yet. Once stable we will need another patch to declare it as so.

    This patch also deprecates the v2 end-point in documentation and by adding the
    same headers we used to use for the v1 end-point.

* (PDB-470) Provide new db setting 'statements-cache-size' with a default of 1000

    This setting adjusts how many SQL prepared statements get cached via BoneCP.

    By using this setting we've seen an almost 40% decrease in wall clock time it
    takes to store a new catalog.

    This patch adds the new configuration item as a user configurable one, with a
    default set to 1000 for now. Documentation has also been added for this
    setting.

* (PDB-221) Add facts to import/export

    This commit imports/exports facts similar to how we currently import/export
    catalogs and reports. Anonymize doesn't currently work for facts, which is
    going to be added separately.

* (PDB-469) - Support Anonymizing Facts

    This commit adds support for the anonymization of facts. The levels of
    anonymization supported are:

    - none - no anonymization
    - low - only values with a fact name of secret, password etc
    - moderate - recognized "safe" facts are untouched, recognized
                                facts with sensitive information (i.e. ipaddress)
                                have their values anonymized
    - full - all fact names and values are anonymized


Deprecations and potentially breaking changes:

* (PDB-88, PDB-271) JDK 1.6 no longer supported

* (PDB-308) Drop 2.7.x support

    This patch removes support for Puppet 2.7.x in several ways:

    * New check for every entry point in terminus will return an error if the
        version of Puppet is not supported. This is done in a 'soft' manner to
        avoid Puppet from not working.
    * Documentation now only references Puppet 3
    * Documentation now states only latest version of Puppet is supported
    * Packaging now has hard dependencies on the latest version of Puppet
    * Contrib gemspec has been updated
    * Gemfile for tests have been updated

* (PDB-552) Pin support for Puppet to 3.5.1 and above

    Starting PuppetDB with a Puppet earlier than 3.5.1 will now fail on startup.

* (PDB-605) Pin facter requirement to 1.7.0

    Using prior versions of Facter will cause PuppetDB to fail on startup

* (PDB-592) Removing support for Ubuntu Raring

    Raring went EOL in Jan 2014, so we are no longer building packages for it.

* (PDB-79) Drop support for Postgres < 8.4

    PuppetDB will now log an error and exit if it connects to an instance of Postgres
    older than 8.4. Users of older versions will need to upgrade (especially EL 5
    users as it defaults to 8.1).    The acceptance tests for EL 5 have been updated
    to be explicit about using Postgres 8.4 packages instead.

* (PDB-204) Ensure all commands no longer need a serialized payload

    Some previous commands required the payload of the command to be
    JSON serialized strings as opposed to the relevant JSON type
    directly in the payload. All commands no longer require the payload
    to be serialized to a string first.

* (PDB-238) - Remove v1 API

    This commit removes the v1 API and builds on the HTTP api refactor.
    This commit contains:

    - Remove all v1 namespaces and the namespaces calling them
    - Remove api tests excercising the v1 routes
    - Remove v1 references in the docs

* (PDB-354) Deprecate old versions of commands

    This patch drops a warning whenever an old version of the commands API is used
    and updates the documentation to warn the user these old commands are
    deprecated.

* (PDB-570) Remove planetarium endpoint

    Old endpoint that had significant overlap with the current catalogs endpoint

* (PDB-113) Remove swank

    As swank is now a deprecated project. This patch removes swank support
    completely from the code base.


Notable improvements and fixes:

* (PDB-473) Support POST using application/json data in the body

    This patch adds to the commands end-point the ability to simply POST using
    application/json with the JSON content in the body.

    It also switches the terminus to use this mechanism.

    We found that the url encode/decode required to support x-www-form-urlencoded
    was actually quite an overhead in a number of ways:

    * The urlencode on the terminus added overhead
    * The urldecode in the server added overhead
    * The interim strings created during this encode/decode process can get quite
        large increasing the amount of garbage collection required

    This feature has been implemented by providing a new middleware that will move
    the body into the parameter :body-string of the request when the content-type
    is not set to application/x-www-form-urlencoded. This provides a convenient
    backwards compatible layer so that the old form url encoding can still be
    supported for older versions of the API.

* (PDB-567,191) Use hash not config_version for report export files

    This fixes a bug related to config_versions containing characts not
    safe to be use in file names (such as '/').

* (PDB-518) Fix bug storeconfig export of arrays

    For exported Resources with parameters which value is a Array the
    storeconfig export fails to collect them. Instead of collecting all
    the parameter values into a array it simply override the value with
    each value in turn.

* (PDB-228) Use JSON in terminus instead of PSON

    The PuppetDB API specifies that it is JSON, so we should parse it as
    that and not as PSON.

    Some Puppet classes (Puppet::Node and Puppet::Node::Facts) don't
    support JSON serialization, so continue to use PSON serialization
    for them. In Puppet 3.4.0+ they have methods to do seralization in
    other formats than PSON though, so once support for older versions
    of Puppet is dropped they can be seralized in JSON as well.

* (PDB-476) Decorate the terminus code with Puppet profiling blocks

    This patch adds some select profiling blocks to the PuppetDB terminus code.

    The profiler is provided by puppet core from Puppet::Util::Puppetdb#profile,
    which has recently become public for our use. We provide here in our own utils
    library our own wrapper implementation that can be mixed in.

    Key areas of our terminus functionality have now been profiled with this
    patch:

    * Entry points are profiled and identified by their entry methods (save, find,
        search etc.)
    * Remote calls, HTTP gets/posts
    * Code that does any form of encoding/decoding that might be potentially slow
        at capacity.

    The style of messages I've used follow along with the existing Puppet profiling
    examples already in place so as to be readable together. We have prefixed our
    profile message with "PuppetDB" for easy searchability also.

    I have provided a small FAQ entry that explains in brief the process of
    debugging, although we lack something to link to in Puppet for a more detailed
    explanation. This will probably need to be fixed if better documentation comes
    available.

* (PDB-472) - Annotate MQ messages without parsing payload

    Received time and a UUID are currently added to incoming (via HTTP) messages
    before placing them on the queue. This commit adds those annotations to the
    MQ message header no longer requires parsing the incoming message payload
    before placing it on the queue.

* (PDB-87) Port PuppetDB to TrapperKeeper

    TrapperKeeper is a new container that PuppetDB will be deployed in.
    This is mainly a refactoring of existing code and error handling to
    use the centralized TrapperKeeper service. More information on
    TrapperKeeper can be found here:
    http://puppetlabs.com/blog/new-era-application-services-puppet-labs.

* (PDB-401) Upgrade to TrapperKeeper 0.3.4

    This commit updates PuppetDB to use the new trapperkeeper 0.3.4
    API.    This includes:

    * Slightly modified syntax for defining services and service
        lifecycle behavior
    * Switch from log4j to logback, update documentation and packaging
        accordingly
    * Switch from jetty7 to jetty9
    * Add example of how to use "reloaded" interactive development pattern
        in REPL
    * Upgrade to kitchensink 0.5.3, with bouncycastle fix for improved
        HTTPS performance

* (PDB-529) Added latest-report? example to the events docs

* (PDB-512) Upgrade to Clojure 1.6.0

* (PDB-521) Switch to using /dev/urandom (using java.security.egd)

* (PDB-564) Added OpenBSD-specific variables to puppetdb.env

    Adding OpenBSD specific variables allows the OpenBSD package
    maintained downstream in the OpenBSD ports tree to be greatly
    simplified.

* (PDB-177) Replace ssl-host default with 0.0.0.0

    By trying to use a hostname, the amount of issues people suffer with during
    setup times related to hostname resolution is quite high. This patch
    replaces the hostname with 0.0.0.0 which by default listens on all
    interfaces.

* (PDB-402) Remove ahead-of-time compilation

    This patch removes AOT compilation from our leiningen project and updates
    all relevant shell scripts to use the non-AOT methodology for invoking
    clojure projects.

* (PDB-576) Update beaker tests to use host.hostname instead of host.name

* (PDB-481) Added Arch Linux build/install support

* (PDB-572) New community packages of puppet install in vendorlibdir

* (PDB-575) Updated install from source docs

* (PDB-254)  Change benchmark to mutate catalog resources and edges send a defined number of messages

    Previously benchmark.clj when it would mutate a catalog would only
    add a single resource.  This change will add a new resource or
    mutate a random existing resource.  It will also add a new or
    change an existing edge.    One of these four mutations is picked
    at random.  This commit also adds a new parameter to benchmark.clj
    to allow the syncronous sending of a specified number of commands
    (per host) via the -N argument

* (PDB-591) Allow gem source to come from env vars

* (PDB-595) Added docs for the load testing and benchmarking tool

    Mostly a developer tool, but documented how to use it in case it's
    useful to others. See "Load Testing" in the Usage / Admin section of
    the docs site

* (PDB-602) Updated acceptance tests to use a proper release of leiningen

* (DOCUMENT-6) Update config page for PuppetDB module's improved settings behavior


1.6.3
-----

PuppetDB 1.6.3 is a bugfix release.

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

PuppetDB 1.6.2 is a bugfix release.

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

    This commit changes the order that the filtering happens in.    We
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

PuppetDB 1.6.0 is a performance and bugfix release.

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

PuppetDB 1.5.0 is a new feature release.    (For detailed information about
any of the API changes, please see the official documentation for PuppetDB 1.5.)

Notable features and improvements:

* (#21520) Configuration for soft failure when PuppetDB is unavailable

    This feature adds a new option 'soft_write_failure' to the puppetdb
    configuration.  If enabled the terminus behavior is changed so that if a
    command or write fails, instead of throwing an exception and causing the agent
    to stop it will simply log an error to the puppet master log.

* New v3 query API

    New `/v3` URLs are available for all query endpoints.    The `reports` and
    `events` endpoints, which were previously considered `experimental`, have
    been moved into `/v3`.  Most of the other endpoints are 100% backwards-compatible
    with `/v2`, but now offer additional functionality.  There are few minor
    backwards-incompatible changes, detailed in the comments about individual
    endpoints below.

* Query paging

    This feature adds a set of new HTTP query parameters that can be used with most
    of the query endpoints (`fact_names`, `facts`, `resources`, `nodes`, `events`,
    `reports`, `event-counts`) to allow paging through large result sets over
    multiple queries.    The available HTTP query parameters are:

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
        available that match the query.    (Mainly useful in combination with `limit`.)

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
    the PuppetDB server.    This can be used as input to time-based queries
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
    events, so these resources would not show up in the PuppetDB report.    This is
    really a bug in Puppet (which should be fixed as of Puppet 3.3), but the PuppetDB
    report processor is now smart enough to detect this case and synthesize a failure
    event for the resource, so that the failure is at least visible in the PuppetDB
    report data.

* Filter out the well-known "Skipped Schedule" events: in versions of Puppet prior
    to 3.3, every single agent report would include six events whose status was
    `skipped` and whose resource type was `Schedule`.    (The titles were `never`,
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
    passing 'commands', 'resources',    and 'metrics'.

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
    representation of the puppet version along with each report.    Previously,
    this column could contain a maximum of 40 characters, but for
    certain builds of Puppet Enterprise, the version string could be
    longer than that.    This change simply increases the maximum length of
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
    does not include the Diffie-Hellman variants.    When this issue is
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
    much more interesting set of queries against report data.    You can now query
    for events by status (e.g. `success`, `failure`, `noop`), timestamp ranges,
    resource types/titles/property name, etc.    This should make the report
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
