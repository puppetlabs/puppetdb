---
title: "PuppetDB 5.1: Contributing to PuppetDB"
layout: default
---

[configure_postgres]: ./configure.markdown#using-postgresql

# How to contribute

Third-party patches are essential for keeping puppet great. We simply can't
access the huge number of platforms and myriad configurations for running
puppet. We want to keep it as easy as possible to contribute changes that
get things working in your environment. There are a few guidelines that we
need contributors to follow so that we can have a chance of keeping on
top of things.

## Getting Started

* Make sure you have a [Jira account](http://tickets.puppetlabs.com)
* Make sure you have a [GitHub account](https://github.com/signup/free)
* Submit a ticket for your issue, assuming one does not already exist.
  * Clearly describe the issue including steps to reproduce when it is a bug.
  * Make sure you fill in the earliest version that you know has the issue.
* Fork the repository on GitHub

## Making Changes

* Create a topic branch from where you want to base your work.
  * This is usually the master branch.
  * Only target release branches if you are certain your fix must be on that
    branch.
  * To quickly create a topic branch based on master; `git checkout -b
    fix/master/my_contribution master`. Please avoid working directly on the
    `master` branch.
* Make commits of logical units.
* Check for unnecessary whitespace with `git diff --check` before committing.
* Make sure your commit messages are in the proper format.

```
    (PUP-1234) Make the example in CONTRIBUTING imperative and concrete

    Without this patch applied the example commit message in the CONTRIBUTING
    document is not a concrete example.  This is a problem because the
    contributor is left to imagine what the commit message should look like
    based on a description rather than an example.  This patch fixes the
    problem by making the example concrete and imperative.

    The first line is a real life imperative statement with a ticket number
    from our issue tracker.  The body describes the behavior without the patch,
    why this is a problem, and how the patch fixes the problem when applied.
```

* Make sure you have added the necessary tests for your changes.
* Run _all_ the tests to assure nothing else was accidentally broken.

### Testing

To run the local unit or integration tests, you will need a
[configured PostgreSQL server][configure_postgres], and you will need
to create the test users:

    $ createuser -DRSP pdb_test
    $ createuser -dRsP pdb_test_admin

You will also need to set the following environment variables if the
default values aren't appropriate:

  * `PDB_TEST_DB_HOST` (defaults to localhost)
  * `PDB_TEST_DB_PORT` (defaults to 5432)
  * `PDB_TEST_DB_USER` (defaults to `pdb_test`)
  * `PDB_TEST_DB_PASSWORD` (defaults to `pdb_test`)
  * `PDB_TEST_DB_ADMIN` (defaults to `pdb_test_admin`)
  * `PDB_TEST_DB_ADMIN_PASSWORD` (defaults to `pdb_test_admin`)

Then you can run the unit tests:

    $ lein test

And if you'd like to preserve the temporary test databases on failure, you can
set `PDB_TEST_PRESERVE_DB_ON_FAIL` to true:

    $ PDB_TEST_KEEP_DB_ON_FAIL=true lein test

To run the integration tests, you'll need to ensure you have a
suitable version of Ruby and Bundler available, and then run

    $ ext/bin/config-puppet-test-ref
    $ ext/bin/config-puppetserver-test-ref

The default puppet and puppetserver versions are recorded in
`ext/test-conf/`.  You can request specific versions of puppet or
puppetserver by specifying arguments to the config tools like this:

    $ ext/bin/config-puppet-test-ref 5.3.x
    $ ext/bin/config-puppetserver-test-ref 5.1.x

Run the tools again to change the requested versions, and `lein
distclean` will completely undo the configurations.

After configuration you should be able to run the tests by specifying
the `:integration` selector:

    $ lein test :integration

To run the local rspec tests (e.g. for the PuppetDB terminus code),
you must have run `config-puppet-test-ref` as described above, and
then from within the `puppet/` directory run:

    $ bundle exec rspec spec

### Cleaning up

Running `lein clean` will clean up the relevant items related to
Clojure, but won't affect some other things, including the integration
test configuration.  To clean up "everything", run `lein distclean`.

## Making Trivial Changes

### Documentation

For changes of a trivial nature to comments and documentation, it is not
always necessary to create a new ticket in Jira. In this case, it is
appropriate to start the first line of a commit with '(doc)' instead of
a ticket number.

```
    (doc) Add documentation commit example to CONTRIBUTING

    There is no example for contributing a documentation commit
    to the Puppet repository. This is a problem because the contributor
    is left to assume how a commit of this nature may appear.

    The first line is a real life imperative statement with '(doc)' in
    place of what would have been the ticket number in a
    non-documentation related commit. The body describes the nature of
    the new documentation or comments added.
```

## Submitting Changes

* Sign the [Contributor License Agreement](http://links.puppetlabs.com/cla).
* Push your changes to a topic branch in your fork of the repository.
* Submit a pull request to the repository in the puppetlabs organization.
* Update your Jira ticket to mark that you have submitted code and are ready for it to be reviewed (Status: Ready for Merge).
  * Include a link to the pull request in the ticket.
* After feedback has been given we expect responses within two weeks. After two
  weeks will may close the pull request if it isn't showing any activity.

# Additional Resources

* [Puppet community guidelines](https://docs.puppet.com/community/community_guidelines.html)
* [Bug tracker (Jira)](http://tickets.puppetlabs.com)
* [Contributor License Agreement](http://links.puppetlabs.com/cla)
* [General GitHub documentation](http://help.github.com/)
* [GitHub pull request documentation](http://help.github.com/send-pull-requests/)
* #puppet-dev IRC channel on freenode.org
