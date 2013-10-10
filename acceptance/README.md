Acceptance Testing
------------------

This README outlines how to run tests using the system testing framework `beaker`, specifically for PuppetDB.

## Quick Start

The acceptance tests utilise the gem `beaker`, and are initiated by the rake task `rake test:beaker`.

An example of how to run the tests:

    $ bundle install
    $ rake test:beaker

However, there are a number of environment variables that can be utilised to modify the build.

An example of how to run a package based build, with vagrant/virtualbox on Centos 6 would be:

    PUPPETDB_INSTALL_TYPE='package' \
    PUPPETDB_EXPECTED_RPM_VERSION="1.3.3.73-1" \
    rake test:beaker

Source based build, from a particular branch:

    PUPPETDB_REPO_PUPPETDB='git://github.com/puppetlabs/puppetdb#1.3.x'
    rake test:beaker

EC2 build from source on Debian 6:

    BEAKER_CONFIG="ec2-west-debian6-64mda-64a" \
    PUPPETDB_REPO_PUPPETDB="git://github.com/puppetlabs/puppetdb#1.3.x" \
    rake test:beaker

EC2 build with packages on Debian 6:

    BEAKER_CONFIG="ec2-west-debian6-64mda-64a" \
    PUPPETDB_INSTALL_TYPE='package' \
    PUPPETDB_PACKAGE_REPO_URL="http://puppetdb-prerelease.s3.amazonaws.com/puppetdb/1.3.x" \
    PUPPETDB_EXPECTED_DEB_VERSION="1.3.3.73-1puppetlabs1" \
    rake test:beaker

## How to set options

## PuppetDB Specific Options

You can set these options in one of two ways; either by specifying them as a key-value
pair in the hash that you return from your "--options" file, or by setting
environment variables.  The symbols listed below are the keys you would put
in your hash; the environment variable names are the same but uppercased
(shown in parens below, for all of your copy'n'paste needs):

* `:puppetdb_install_type` (`PUPPETDB_INSTALL_TYPE`) : Can be one of `:git`,
  `:pe` or `:package`. This dictates how the software gets installed.

* `:puppetdb_install_mode` (`PUPPETDB_INSTALL_MODE`) : This setting is only
  relevant when our `install_type` is set to `:package` (meaning that we are
  running the tests against OS packages rather than against source code pulled
  from git).  Legal values are `:install` and `:upgrade`; if set to `:install`,
  the test will be run against a freshly-installed package built from the
  latest development source code.  If set to `:upgrade`, we will first install
  the latest release packages, then start puppetdb, then upgrade to the new package
  built from development source code.

* `:puppetdb_database` (`PUPPETDB_DATABASE`) : This determines which database
  backend the tests will be run against.  Legal options are `:postgres` and
  `:embedded`.

* `:puppetdb_validate_package_version` (`PUPPETDB_VALIDATE_PACKAGE_VERSION`) :
  This boolean determines whether or not we attempt to verify that the installed
  package version matches what we expect.  This is mostly useful in CI, to make
  sure we're testing the right code.  Legal values are `:true` and `:false`.

* `:puppetdb_expected_rpm_version` (`PUPPETDB_EXPECTED_RPM_VERSION`) :
  This is only relevant if `:puppetdb_validate_package_version` is set to `:true`.
  In that case, this setting may contain a version number string (including the
  rpm release specifier), and the tests will fail on RedHat-based systems if, when
  we install the development puppetdb package, its version number does not match
  this string.

* `:puppetdb_expected_deb_version` (`PUPPETDB_EXPECTED_DEB_VERSION`) :
  This is only relevant if `:puppetdb_validate_package_version` is set to `:true`.
  In that case, this setting may contain a version number string, and the tests
  will fail on Debian-based systems if, when we install the development puppetdb
  package, its version number does not match this string.

* `:puppetdb_use_proxies` (`PUPPETDB_USE_PROXIES`) : This determines whether or
  not the test run will configure package managers (apt/yum) and maven to use
  our internal proxy server.  This can provide a big speedup for CI, but is
  not desirable for remote employees during development.  Legal values are `:true`
  and `:false`.

* `:puppetdb_purge_after_run` (`PUPPETDB_PURGE_AFTER_RUN`) : This determines
  whether or not the post-suite cleanup phase will remove packages and perform
  exhaustive cleanup after the run.  This is useful if you would like to avoid
  resetting VMs between every run of the acceptance tests.  Defaults to `:true`.

* `:puppetdb_package_build_host` (`PUPPETDB_PACKAGE_BUILD_HOST`) : This specifies
  the hostname where the final packages built by the packaging job are available.
  This should typically not need to be overridden, as it defaults to the
  well-known host name provided by our release engineering team.

* `:puppetdb_package_repo_host` (`PUPPETDB_PACAKGE_REPO_HOST`): This specifies
  the hostname where the final apt/yum repos will be deployed and accessible to
  the test nodes.  When testing under EC2, this will be an s3 "hostname" that
  differs from our default for internal VMs.  This should be overridden by
  jenkins jobs that are running in EC2.

* `:puppetdb_package_repo_url` (`PUPPETDB_PACKAGE_REPO_URL`) : By default,
  the test setup will install the latest 'master' branch of puppetdb dev packages
  from puppetlabs.lan; however, if this option is set, then it will try to use
  the apt/yum repos from that url (appending 'debian', 'el', etc.) instead.  This
  is required for jobs that will be running outside of the puppetlabs LAN.

* `:puppetdb_repo_(puppet|facter|hiera)` (`PUPPETDB_REPO_(PUPPET|FACTER|HIERA)`) :
  Specify the git repository and reference to use for installing Puppet/Facter/Hiera
  from source. This is primarily so we can test against alternate versions of
  Puppet, so if the Puppet repo is not specified we fall back to using packages.

* `:puppetdb_repo_puppetdb` (`PUPPETDB_REPO_PUPPETDB`) :
  Specify the git repository and reference for where we are to download the
  PuppetDB source code.

* `:puppetdb_git_ref` (`REF`) :
  Specify the specific git ref that the tests should be run against.  This should
  almost always be passed in by the Jenkins job, and not overridden by configuration.

## Beaker Specific Options

These options are only environment variables, and are specific to the `test:beaker` rake task.

###`BEAKER_TYPE`

Can be one of:

* _git_: this changes the installation type to use `git`, and is really associated with FOSS based tests.
* _pe_: this changes the installation type for `pe` based tests.

The default is `git`.

*Note:* In the future this option will be removed from Beaker.

###`BEAKER_CONFIG`

This variable allows you to select a host configuration. See the directory `acceptance/config/` for a list of availabe configurations (the .cfg extension is not necessary).

###`BEAKER_OPTIONS`

This allows one to choose from a number of different configuration option files. Including:

* postgres
* embedded\_db
* puppet\_enterprise

These files are listed in the directory `acceptance/options`, so you should look at the detail in those files more more information.

###`BEAKER_PRESERVE_HOSTS`

When this setting is `true` it will stop the removal of the virtual machine once the task has been completed.

####`BEAKER_COLOR`

Set this to `true` to enable color in Beaker. Setting to `false` will disable color in Beaker.

####`BEAKER_XML`

Set this to `true` to produce XML that can be consumed by Jenkins for test reporting.

## Extra Information

### EC2 Benchmarking

We determined there was a cost/benefit sweet spot by moving to `c1.medium` from `m1.small`, the calculations are here:

    42 minutes, 6 cents:    m1.small       <-- legacy
    24 minutes, 12 cents:   m1.medium
    19 minutes, 14.5 cents: c1.medium      <-- good bang for buck
    19 minutes, 24 cents:   m1.large
    17 minutes, 48 cents:   m1.xlarge
    15 minutes, 41 cents:   m2.xlarge      <-- next best jump after c1.medium?
    15 minutes, 82 cents:   m2.2xlarge
    14 minutes, 50 cents:   m3.xlarge
