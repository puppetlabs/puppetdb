---
title: "Contributing to PuppetDB"
layout: default
---

[configure_postgres]: ./configure.markdown#using-postgresql

# Contributing to PuppetDB

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
  * This is usually the main branch.
  * Only target release branches if you are certain your fix must be on that
    branch.
  * To quickly create a topic branch based on main; `git checkout -b
    fix/main/my_contribution main`. Please avoid working directly on the
    `main` branch.
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

Before you do anything else, you may want to consider setting
`PUPPET_SUPPRESS_INTERNAL_LEIN_REPOS=1` in your environment.  We'll
eventually make that the default, but for now that setting may help
avoid delays incurred if lein tries to reach unreachable internal
repositories.

The easiest way to run the tests until you need to do it often is to
use the built-in sandbox harness.  If you just want to check some
changes against "all the normal tests", this should work (assuming
you're not running a server on port 34335):

    $ ext/bin/test-config --set pgport 34335
    $ ext/bin/test-config --reset puppet-ref
    $ ext/bin/test-config --reset puppetserver-ref
    $ ext/bin/run-normal-tests

This will run the core, integration, and external tests, and in some
cases may be all that you need, but in many cases, you may want to be
able to run tests more selectively as detailed below.  Copies of tools
like `lein` and `pgbox` may be downloaded and installed to a temporary
directory during the process, if you don't already have the expected
versions.

When using the sandbox, you need to either specify the PostgreSQL port
it should use by providing a `--pgport PORT` argument to each relevant
test invocation, or you can set a default (as above) for the source
tree:

    $ ./ext/bin/test-config --set pgport 34335


After you've set the default pgport, you should be able to run the core
tests like this:

    $ ext/bin/boxed-core-tests -- lein test

Similarly you should be able to configure and run the integration
tests against the default Puppet and Puppetserver versions like this:

    $ ext/bin/test-config --reset puppet-ref
    $ ext/bin/test-config --reset puppetserver-ref
    $ ext/bin/boxed-integration-tests \
        -- lein test :integration

Note that you only need to configure the puppet-ref and
puppetserver-ref one time for each tree, but you can also change the refs
when you like with `test-config`:

    $ ext/bin/test-config --set puppet-ref 5.5.x
    $ ext/bin/test-config --set puppetserver-ref 5.2.x

Running `--reset` for an option resets it to the tree default, and at
the moment, you'll need to do that manually whenever you're using the
default and the relevant `*-default` file in ext/test-conf changes in
the source.

The sandboxes are destroyed when the commands finish, but you can
arrange to inspect the environment after a failure like this:

    $ ext/bin/boxed-integration-tests \
        -- bash -c 'lein test || bash'

which will drop you into a shell if anything goes wrong.

To run the local rspec tests (e.g. for the PuppetDB terminus code),
you must have configured the `puppet-ref` via `ext/bin/test-config` as
described above, and then from within the `puppet/` directory you can
run:

    $ bundle exec rspec spec

If you'd like to preserve the temporary test databases on failure, you can
set `PDB_TEST_PRESERVE_DB_ON_FAIL` to true:

    $ PDB_TEST_KEEP_DB_ON_FAIL=true lein test

The sandboxed tests will try to find and use the version of PostgreSQL
specified by:

    $ ./ext/bin/test-config --get pgver

Unless you override that with `test-config`:

    $ ext/bin/test-config --set pgver 9.6

Given just the version, the tests will try to find a suitable
PostgreSQL installation, but you can specify one directly like this:

    $ ext/bin/test-config --set pgbin /usr/lib/postgresql/9.6/bin

at which point the pgver setting will be irrelevant until/unless you
reset pgbin:

    $ ext/bin/test-config --reset pgbin

If you're running the tests all the time, you might want to set up
your own persistent sandbox instead (`ext/bin/with-pdbbox` does
something similar) so you can run tests directly against that:

    $ ext/bin/pdbbox-init \
      --sandbox ./test-sandbox \
      --pgbin /usr/lib/postgresql-9.6/bin \
      --pgport 17961

Then you can start and stop the included database server like this:

    $ export PDBBOX="$(pwd)/test-sandbox"
    $ ext/bin/pdbbox-env pg_ctl start -w
    $ ext/bin/pdbbox-env pg_ctl stop

and when the database server is running you can run the tests like
this:

    $ export PDBBOX="$(pwd)/test-sandbox"
    $ ext/bin/pdbbox-env lein test

Note that in cases where durability and realistic performance aren't
important (say for routine `lein test` runs), you may see substantially
better performance if you disable postgres' fsync calls with `-F` like
this:

    $ ext/bin/pdbbox-env pg_ctl start -o -F -w

Before you can run the integration tests directly, you'll need to
configure the puppet and puppetserver versions you want to use.
Assuming you have suitable versions of Ruby and Bundler available, you
can do this:

    $ ext/bin/test-config --reset puppet-ref
    $ ext/bin/test-config --reset puppetserver-ref

The default puppet and puppetserver versions are recorded in
`ext/test-conf/`.  You can request specific versions of puppet or
puppetserver like this:

    $ ext/bin/test-config --set puppet-ref 5.3.x
    $ ext/bin/test-config --set puppetserver-ref 5.3.x

Run the tools again to change the requested versions, and `lein
distclean` will completely undo the configurations.

After configuration you should be able to run the tests by specifying
the `:integration` selector:

    $ export PDBBOX="$(pwd)/test-sandbox"
    $ ext/bin/pdbbox-env lein test :integration

You can also run puppetdb itself with the config file included in the
sandbox:

    $ export PDBBOX="$(pwd)/test-sandbox"
    $ ext/bin/pdbbox-env lein run services \
        -c test-sandbox/conf.d

And finally, you can of course set up and [configure your own
PostgreSQL server][configure_postgres] for testing, but then you'll
need to create the test users:

    $ createuser -DRSP pdb_test
    $ createuser -dRsP pdb_test_admin

and do the other things that `pdbbox-init` normally handles, like
setting environment variables if the default values aren't
appropriate, etc.:

  * `PDB_TEST_DB_HOST` (defaults to localhost)
  * `PDB_TEST_DB_PORT` (defaults to 5432)
  * `PDB_TEST_DB_USER` (defaults to `pdb_test`)
  * `PDB_TEST_DB_PASSWORD` (defaults to `pdb_test`)
  * `PDB_TEST_DB_ADMIN` (defaults to `pdb_test_admin`)
  * `PDB_TEST_DB_ADMIN_PASSWORD` (defaults to `pdb_test_admin`)

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
