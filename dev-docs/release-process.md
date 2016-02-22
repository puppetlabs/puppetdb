## Releasing PuppetDB x.y.z

NOTE: Read over this doc before starting and correct any wrong information when
done.

1. Reconcile tickets as described in the relevant release ticket. I do this
   manually by inspecting the git log and comparing against
   'Project=PDB and fixVersion="PDB x.y.z"' in JIRA. There's also a script
   called ticketmatch.rb that claims to do the same, but I'm not sure how to
   use it.

2. Write release notes and submit a PR to core. Ping the team to let them know
   you need review.

3. Bump the version in the FOSS project.clj to the proper non-SNAPSHOT version
   and push straight to the target branch without a PR. This will kick off an
   acceptance test run. This can be done before the release notes PR is in.

4. While the test run is going, write the announcement email. Old release
   emails are here: bit.ly/21aIqeL . The easiest thing to do is copy one and
   change the dates/versions. Once finished copy paste the doc contents into an
   email titled "(DRAFT) Announce: PuppetDB x.y.z is now available" or similar
   and send to the puppetdb team for review.

5. When the CI job is complete, ensure that the PDB artifact exists in nexus at
   http://nexus.delivery.puppetlabs.net/#nexus-search;quick~puppetdb by
   clicking on the line that says puppetlabs | puppetdb | blah and scrolling
   through the versions for the unqualified x.y.z version.

6. Bump the project.clj version in pe-puppetdb-extensions and push straight to
   the target branch. This will trigger the on-merge extensions build but it's
   the same code that was tested earlier (we should fix this part of the
   process).

7. While that job runs, tell your releng liaison that you're ready for FOSS
   packages.

8. Once packages are ready, smoke test them following your favorite smoke
   testing guide. My favorite is here:
   https://confluence.puppetlabs.com/display/PP/Smoke+Testing+Guide+for+PDB+Releases

   Prioritize platforms that aren't acceptance tested if any aren't. If they
   all are then I usually test the latest debian and latest centos anyway. This
   part should be parallelized across multiple team members.

9. Ping the team plus releng and say it's time for go/no-go.

10. Once everyone is go releng will start to ship PDB. Once packages are live,
    send your announcement email after adjusting it for feedback.

11. Update dujour. Get the url/password from someone on the team.

12. Prepare the PE components for release by starting at step 4 here:
https://github.com/puppetlabs/pe-puppetdb-extensions/blob/master/dev-docs/building_for_pe.markdown#building-for-release

  This part can be decoupled from the rest of the release because it doesn't
  require releng involvement. It doesn't really even need to happen the same
  day, although it's nicer if it does.  Once the pe packages get built and are
  visible on nexus, promote the version to PE via kerminator. Make sure the
  promotion succeeds. Once promoted, get releng to make you a tag on the
  extensions repo.  After that you're done.

17. If inclined, have a celebratory beer.
