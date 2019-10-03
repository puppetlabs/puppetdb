# Reconciling Git commits and Jira tickets

The rake task `release:reconcile` will verify three things. First, that every commit either,
has an associated PDB ticket, or is prefixed by one of `(docs)`, `(maint)`, `(i18n)`.
Second, that every Jira ticket that has the fixVersion `PDB <release_version>` has an associate
git commit. And third it will verify that every commit whose message includes `(PDB-####)`
has a Jira ticket with the fixVersion `PDB <release_version>`.

It does not require authentication as it relies on our Jira API
being open to the public, and the git history residing on your computer.

## How to run

First, ensure you have checked out both `puppetdb` and `pe-puppetdb-extensions` and that both
branches are up to date with the `puppetlabs/[pupppetdb/pe-puppetdb-extension]` repo. This
task will look at the git history in your currently active branches, if it is inaccurate, your
results will be inaccurate as well.

```
bundle install
bundle exec rake release:reconcile[<release_version>,<previous_version>]
```

The rake task always requires 2 arguments to be provided, the version you will be releasing,
and the previously released version. Below are the two cases you'll run into.


For a z release, the previous release is always the proceeding release with the same `X.Y` version.

```
# Z release
bundle exec rake release:reconcile[5.2.10,5.2.9]
```

For an x or a y release, the previous version is the most recent release on the most recent y branch.
```
# X/Y release
# If the previous release was also a y release
bundle exec rake release:reconcile[6.7.0,6.6.0]

# or if y release has a maintained branch
bundle exec rake release:reconcile[6.4.0,6.3.1]
```

## Options

### Environment Variables

Environment variables all have a default value in the rake task,
but are also configurable on the command line.

* `PDB_PATH` - path to the PuppetDB repo (Default: `./`)
* `PDB_PE_PATH` - path to the pe-puppetdb-extensions repo (Default `../pe-puppetdb-extensions`)

## Output

The rake task will output nothing if all commits have a corresponding `PDB` ticket with the correct
`fixVersion`.

For commits that are not prefixed by a `(PDB-###)`, `(docs)`, `(maint)`, or `(i18n)`
it will output
```
INVESTIGATE! <repo> <commit message>
```

For mismatches between Jira and Git it will print one of
```
<ticket> exists in JIRA with fixVersion '<fix_version>', but there is no corresponding git commit
```
or
```
<ticket> has a git commit(s) <sha(s)> in <repo>, but its JIRA ticket does not have fixVersion '<fix_version>'
```
