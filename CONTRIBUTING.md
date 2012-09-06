# How to contribute

* Visit the [PuppetDB board on Trello](http://links.puppetlabs.com/puppetdb-trello)
* Make sure you have a [Redmine account](http://projects.puppetlabs.com)
* Make sure you have a [GitHub account](https://github.com/signup/free)
* Submit a ticket for your issue, assuming one does not already exist.
  * Clearly describe the issue including steps to reproduce when it is a bug.
  * Make sure you fill in the earliest version that you know has the issue.
* Fork the repository on GitHub

## Making Changes

* Create a branch off of the branch you want to base off of.
  * This is usually the master branch.
  * Only target release branches if you are certain your fix must be on that branch.
* Make commits of logical units.
* Check for unnecessary whitespace with "git diff --check" before committing.
* Make sure your commit messages are in the proper format.

````
    (#Ticket Number) What you are changing with this commit

    Describe what happened before. Describe the change in behavior that this
    commit makes.
````

* Make sure you have added the necessary tests for your changes.
* Run _all_ the tests to assure nothing else was accidentally broken.

## Submitting Changes

* Sign the [Contributor License Agreement](https://projects.puppetlabs.com/contributor_licenses/sign).
* Push your changes to a topic branch in your fork of the repository.
* Submit a pull request to the repository in the puppetlabs organization.
* Update your Redmine ticket to mark that you have submitted code.

# Additional Resources

* [More information on contributing](http://projects.puppetlabs.com/projects/puppet/wiki/Development_Lifecycle)
* [Bug tracker (Redmine)](http://projects.puppetlabs.com)
* [Contributor License Agreement](https://projects.puppetlabs.com/contributor_licenses/sign)
* [General GitHub documentation](http://help.github.com/)
* [GitHub pull request documentation](http://help.github.com/send-pull-requests/)
