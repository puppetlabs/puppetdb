# PuppetDB Acceptance testing options

## How to set options

You can set options in one of two ways; either by specifying them as a key-value
pair in the hash that you return from your "--options" file, or by setting
environment variables.  The symbols listed below are the keys you would put
in your hash; the environment variable names are the same but uppercased
(shown in parens below, for all of your copy'n'paste needs):

## List of supported options:

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

* `:puppetdb_expected_package_version` (`PUPPETDB_EXPECTED_PACKAGE_VERSION`) :
  This is only relevant if `:puppetdb_validate_package_version` is set to `:true`.
  In that case, this setting may contain a version number string, and the tests
  will fail if, when we install the development puppetdb package, its version
  number does not match this string.

* `:puppetdb_use_proxies` (`PUPPETDB_USE_PROXIES`) : This determines whether or
  not the test run will configure package managers (apt/yum) and maven to use
  our internal proxy server.  This can provide a big speedup for CI, but is
  not desirable for remote employees during development.  Legal values are `:true`
  and `:false`.

* `:puppetdb_purge_after_run` (`PUPPETDB_PURGE_AFTER_RUN`) : This determines
  whether or not the post-suite cleanup phase will remove packages and perform
  exhaustive cleanup after the run.  This is useful if you would like to avoid
  resetting VMs between every run of the acceptance tests.  Defaults to `:true`.

* `:puppetdb_package_repo_url` (`PUPPETDB_PACKAGE_REPO_URL`) : By default,
  the test setup will install the latest 'master' branch of puppetdb dev packages
  from puppetlabs.lan; however, if this option is set, then it will try to use
  the apt/yum repos from that url (appending 'debian', 'el', etc.) instead.  This
  is required for jobs that will be running outside of the puppetlabs LAN.
