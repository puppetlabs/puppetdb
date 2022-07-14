# PuppetDB Testing and CI

## Jargon

### spec
A testing specification string. The element is the test flavor and the trailing elements describe dependency information (JDK, PostgreSQL, Puppet, Puppet Server). Examples:
- `core/openjdk8/pg-9.6`
- `int/openjdk11/pup-6.x/srv-6.x/pg-11`
- `core+ext/openjdk11/pg-11/rich`
- `rspec/pup-6.x`

### flavor
The first element of the spec string describing which test suite to run. One of:
- `core` (`lein test`)
- `ext` (external tests on PuppetDB jar)
- `core+ext` (both core and external tests)
- `int` (integration tests with Puppet and Puppet Server)
- `lint` (check Clojure code with Eastwood and clj-kondo)
- `rspec`

### ref
The version number or branch name of an element of the spec. Anything that comes after the hyphen if there is one. The ref of `pg-9.6` is `9.6`. The ref of `pup-6.x` is `6.x`.

### JDK flavor
The JDK package name like `openjdk8`.

## Testing Scripts

### `ext/bin/symlink-relative-to`
Creates symbolic link to a relative path. Relative path is forced even if absolute is given.

### `ext/bin/sha256sum`
Outputs sha256 checksum of STDIN

### `ext/bin/config-puppet-test-ref`
Modify local testing environment to use specific version of Puppet.

### `ext/bin/config-puppetserver-test-ref`
Modify local testing environment to use specific version of Puppet Server.

### `ext/bin/test-config`
Get, set, or reset persistent test configuration values by storing them in local files in the directory `ext/test-conf`

### `ext/bin/flavor-from-spec`
Parses a test spec string and prints the test flavor (type of tests to run like core or lint)

### `ext/bin/check-spec-env`
Ensures that correct JDK is installed and test flavor is valid.

### `ext/bin/spec-includes`
Prints search string if its a component of test spec.

### `ext/bin/jdk-from-spec`
Parses a test spec string and prints the JDK package name

### `ext/bin/prefixed-ref-from-spec`
Parses a test spec for a given element (like pg-13) and prints the ref (version number)

### `ext/bin/jdk-info`
Prints information about installed JDK
- Can output full version (`1.8.0_172`), major version (`8`), or package/spec (`openjdk8`)

### `ext/bin/require-jdk`
Installs JDK onto machine

### `ext/bin/require-leiningen`
Installs Leiningen onto machine

### `ext/bin/require-pgbox`
Installs pgbox onto machine

### `ext/bin/prep-debianish`
Prepares Debian-like Linux machines for running PuppetDB tests

### `ext/bin/prep-macos`
Prepares MacOS machines for running PuppetDB tests

### `ext/bin/pdbbox-init`
Creates a local PuppetDB sandbox directory which contains a PostgreSQL sandbox

### `ext/bin/pdbbox-env`
Sets up environment variables and runs a command using  `pgbox env`

### `ext/bin/with-pdbbox`
Runs a command using new, empheral PuppetDB sandbox (with an ephemeral PostgreSQL sandbox inside of it). Starts PostgreSQL and runs a command using the sandboxes. Destroys sandboxes and stops PostgreSQL afterwards.

### `ext/bin/boxed-core-tests`
Runs a command using ephemeral Leiningen and pgbox executables, as well as ephemeral PuppetDB and PostgreSQL sandboxes. User still needs PostgreSQL and JDK installed. Passes command to `ext/bin/with-pdbbox` which creates the ephemeral sandboxes.

### `ext/bin/boxed-integration-tests`
Exactly the same as `ext/bin/boxed-core-tests` except uses this for the temporary directory name (starts with `int-test`).

### `ext/bin/run-external-tests`
Creates a PuppetDB uberjar and runs some tests on its behavior and output.

### `ext/bin/run-rspec-tests`
Run Ruby rspec tests on Ruby code in `puppet` directory.

### `ext/bin/run-normal-tests`
Runs core, integration, and external tests with `boxed-core-tests`, `boxed-integration-tests`, and `run-external-tests` respectively. Also prints log lines saying which script is running.

### `ext/test/oom-causes-shutdown`
Tests an existing PuppetDB jar to to see if it gracefully shuts down on an OutOfMemoryError when forced to allocate a giant amount of memory.

### `ext/test/top-level-cli`
Tests an existing PuppetDB jar's command line interface by running subcommands and grepping the output.

### `ext/test/schema-mismatch-causes-pdb-shutdown`
Verifies that a PuppetDB jar will periodically check and shut down when the database is at an unrecognized schema (migration) number.

### `ext/test/database-migration-config`
Ensures PuppetDB jar fails to start and logs error message when the PuppetDB PostgreSQL database is not migrated to the most recent migration that PuppetDB knows about.

### `ext/bin/contributors-in-git-log`
Given two git tags, prints out a list of contributors who authored commits in that commit range.

### `ext/bin/render-pdb-schema`
Creates an SVG graph of the PuppetDB PostgreSQL schema for educational purposes. Requires postgresql-autodoc and graphiz.

### `ext/bin/tag-release`
Automates the release tagging process for PuppetDB. This script tags and pushes both release branches for FOSS and extensions (four total branches) at once.

### `pdb`
In top-level of source tree. Starts an existing PuppetDB uberjar and passes all arguments. Allows overriding of Bouncy Castle jars.

## CI Scripts

### `ci/bin/prep-and-run-in`
Prepares the CI machine for tests and runs tests using `ci/bin/run`.

### `ci/bin/run`
Runs tests on a prepared CI machine.

### `ci/bin/travis-on-success`
Runs when FOSS PDB Travis tests succeed. Triggers tests on same branch name for extensions repo. Referenced in `.travis.yml`.

## CI Configuration

### `.travis.yml`
Travis CI configuration. Main CI test runner. Runs core, external, integration, rspec, and container tests in a variety of forms. Notifies Slack with results. See https://travis-ci.com/puppetlabs/puppetdb.

###  `.github/workflows/main.yml`
On pull request and push runs core, external, integration, and rspec tests on MacOS machines. Also runs lint test on an Ubuntu machine.

### `.github/workflows/docs_test.yml`
On pull request runs `dita` doc-building command and tests for failure.

### `.github/workflows/docs_publish.yml`
On push to `doc-latest` or `doc-6.y` builds and uploads docs to Puppet's s3 bucket which gets used by puppet.com/docs.

### `.github/workflows/snyk.yml`
On push to `6.x` and `main`, run Snyk security scanning tests.

## Internal Jenkins CI Configuration
Both FOSS PuppetDB and PuppetDB Extensions repos are tested with other Puppet Enterprise components internally using a [private Jenkins instance](https://jenkins-enterprise.delivery.puppetlabs.net/view/puppetdb). The CI jobs are defined by [Jenkins Job Builder](https://jenkins-job-builder.readthedocs.io/en/latest) YAML files. They live in the [private ci-job-configs repo](https://github.com/puppetlabs/ci-job-configs). The most important PuppetDB files in that repo are:

### `doc/pipelines/enterprise.md`
Overview of whole Jenkins setup. Documents global parameters which can be used by any project.

### `doc/pipelines/puppetdb.md`
Overview of PuppetDB Jenkins setup. Documents PuppetDB-specific parameters.

### `jenkii/enterprise/projects/puppetdb.yaml`
Defines the PuppetDB projects.

### `resources/job-groups/puppetdb.yaml`
Defines the PuppetDB job groups.

## Docker

PuppetDB is published as [Docker image](https://hub.docker.com/r/puppet/puppetdb), although supporting it is not currently a priority.

### `docker/Makefile`
- prep: Git fetches all latest commits and tags
- lint: Lints `docker/puppetdb/Dockerfile` with `hadolint`
- build: Builds image with `docker buildx`
- test: Runs Ruby rspec tests
- push-image: Push docker image to dockerhub
- push-readme: Update dockerhub README
- publish: Runs push-image and push-readme
