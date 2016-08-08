# Releasing PuppetDB x.y.z

NOTE: Read over this doc before starting and correct any wrong information when
done.

## FOSS

0. Create release tickets (ahead of time). Check the names below are correct
   before running. Source for the task is here:
   https://github.com/puppetlabs/packaging/blob/master/tasks/tickets.rake#L416-L417
   
   From the PDB directory:

       rake pl:tickets BUILDER=morgan DEVELOPER=<release QB> WRITER=nick.fagerlund OWNER=beth.cornils TESTER=kurt.wall RELEASE=4.0.0 DATE=2016-02-29 JIRA_USER=<your jira name> PROJECT=PDB

1. Is the code ready for release? Check that the latest nightly build was green
  - stable: https://jenkins-enterprise.delivery.puppetlabs.net/view/puppetdb/view/stable/job/enterprise_puppetdb_init-multijob_periodic-stable/
  - master: https://jenkins-enterprise.delivery.puppetlabs.net/view/puppetdb/view/master/job/enterprise_puppetdb_init-multijob_periodic-master/

2. Ensure all tickets referenced in the commit log have a bug targeted at the
   release, and ensure all tickets targeted at the release have a corresponding
   commit. You can do this manually by inspecting the git log and comparing
   against 'Project=PDB and fixVersion="PDB x.y.z"' in JIRA. 

   You might have a 'ticketmatch' script that purports to do the same thing. Do
   not trust it!

3. Clean up the JIRA version for the current release and prepare for the next
   release.

   - Open https://tickets.puppetlabs.com/plugins/servlet/project-config/PDB/versions
   - Find the '.x' version you're about to release
   - Rename it, replacing the 'x' with a real number
   - Click the '...' button on the right and choose "Release"
   - Create a new '.x' version for future development on that release series.
   - Be sure any tickets not getting fixed in this release are assigned to your
     new '.x' version

4. Write release notes, updating documentation/release_notes.markdown, and
   submit a PR. Ping the team to let them know you need review.

   To write the release notes, look at both the jira tickets with the
   corresponding FixVersion and at the output of
   `git log <last-version>..head --pretty=format:'* %s%n  (%h)%>|(10)%+b%n' --no-merges`

   To generate the contributors list:
   `git log <last-version>..head --format='%aN' | sort -u`
   
5. If this is an X or a Y release, changes need to be made to the `puppet-docs` repo. See [this PR](https://github.com/puppetlabs/puppet-docs/pull/655) for an example.

6. Bump the version in the FOSS project.clj to the proper non-SNAPSHOT version
   and push straight to the target branch without a PR. This will build packages
   and kick off an acceptance test run. This can be done before the release
   notes PR is in.

7. While the test run is going, write the announcement email. Old release
   emails are here: http://bit.ly/21aIqeL . The easiest thing to do is copy one and
   change the dates/versions. Once finished copy paste the doc contents into an
   email titled "(DRAFT) Announce: PuppetDB x.y.z is now available" or similar
   and send to the puppetdb team for review.

8. When the CI job is complete, ensure that the PDB artifact exists in nexus at
   http://nexus.delivery.puppetlabs.net/#nexus-search;quick~puppetdb by
   clicking on the line that says puppetlabs | puppetdb | blah and scrolling
   through the versions for the unqualified x.y.z version.

9. Bump the project.clj version in pe-puppetdb-extensions and push straight to
   the target branch. This will trigger the on-merge extensions build but it's
   the same code that was tested earlier (we should fix this part of the
   process).

10. Run the manual smoke tests. 
   - https://github.com/puppetlabs/pe-puppetdb-extensions/blob/master/dev-docs/smoke_test.org (the new way)
   - https://confluence.puppetlabs.com/display/PP/Smoke+Testing+Guide+for+PDB+Releases (the old way)

   The FOSS packages were automatically created by the CI job and uploaded to
   http://builds.puppetlabs.lan/puppetdb/<version>, so you don't need to do
   anything special to generate them.

   Prioritize platforms that aren't acceptance tested if any aren't. If they
   all are then I usually test the latest debian and latest centos anyway. This
   part should be parallelized across multiple team members.

11. Ping the team plus releng and say it's time for go/no-go.

12. Once everyone is go releng will start to ship the PDB packages. 

## PE

1. Have a FOSS release that you want to do a PE release against, by following
   the steps above. If you need to make such a release just for PE:

   - Update project.clj in puppetdb, removing `-SNAPSHOT` from pdb-version.
     Commit this and push it *directly* to origin/stable or origin/master; our
     build can't deal with such a version existing in a PR.

   - Wait for this to build (or kick off the build by hand); once it has
     succeeded, you should see an artifact in
     [nexus](http://nexus.delivery.puppetlabs.net/#nexus-search;quick~puppetdb)
     with the version you put in project.clj.

2. Update project.clj in pe-puppetdb-extensions, setting pdb-version to the the
   FOSS version you're building against and removing `-SNAPSHOT` from
   pe-version. (these should really be the same, for the sanity's sake) Commit
   and push your changes *directly* to origin/stable or origin/master, for the
   same reason as above.

3. Your change will kick off a build, but this build will *not* put a release
   build in nexus like it does for FOSS. We don't need to wait for this build,
   as this is just a version bump.

4. Manually trigger the pe-puppetdb-extensions packaging job in Jenkins. This
   makes the actual release build and puts it in nexus, then makes packages out
   of it.
   - stable: http://kahless.delivery.puppetlabs.net/view/pe-puppetdb-extensions/view/all/job/enterprise_pe-puppetdb-extensions_packaging_stable/
   - master: http://kahless.delivery.puppetlabs.net/view/pe-puppetdb-extensions/view/all/job/enterprise_pe-puppetdb-extensions_packaging_master/

5. Once the build is done, tell kerminator to promote it. If you were releasing
   pe-puppetdb-extensions version 3.1.2 into PE 2015.3, this would look like:
   `@kerminator promote pe-puppetdb 3.1.2 to 2013.3.x`

6. This kicked off a Jenkins job over at
   http://jenkins-compose.delivery.puppetlabs.net/view/Promotion/job/Package-Promotion/.
   Go babysit it and make sure it actually works.

7. Now you can do some smoke testing; get the packages over at
   http://getpe.delivery.puppetlabs.net/ Note that this automatically builds
   every half hour, so you'll need to wait to get one with your build in it.

8. Find a friend in RelEng to make you some tags.

## Post-release

1. Check the [tarball download page](https://downloads.puppetlabs.com/puppetdb/) to ensure the new release is present

2. Update [dujour](https://updates.puppetlabs.com/dashboard/). Get the password
    from someone on the team.

3. Send the announce email to:
   - puppet-announce@googlegroups.com
   - puppet-users@googlegroups.com
   - puppet-dev@googlegroups.com

4. Get a bit.ly link from the marketing hipchat channel

5. Tweet it

6. Give the tweet link to marketing to they can RT it

7. Send a PSA message in irc #puppet and #puppet-dev

8. Change the subject of the puppetdb hipchat channel

9. Close any tickets that have been resolved for the release.

   https://tickets.puppetlabs.com/issues/?jql=project%20%3D%20PDB%20AND%20resolution%20%3D%20Fixed%20AND%20fixVersion%20%3D%20%22PDB%203.2.4%22%20AND%20status%20%3D%20Resolved

   There is a bulk edit at the top (a gear with the word "Tools"). Should you decide to take this route:
   - Select Bulk Change - All # issues
   - Step 1 - choose all relevant issues (likely all of them)
   - Step 2 - Select "Transition Issues"
   - Step 3 - Select "Closed"
   - Step 4 - Select "Fixed" in Change Resolution.
   - View what is about to change and confirm it. Then commit the change.

10. Consume alcohol
