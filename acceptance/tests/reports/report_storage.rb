require 'json'

test_name "basic validation of puppet report submission" do

  Log.notify "Setting up manifest file"
  manifest = <<MANIFEST
notify { "hi":
  message => "Hi ${::clientcert}"
}
MANIFEST

  tmpdir = master.tmpdir('storeconfigs')

  manifest_file = File.join(tmpdir, 'site.pp')

  create_remote_file(master, manifest_file, manifest)

  on master, "chmod -R +rX #{tmpdir}"

  # TODO: the module should be setting up the report processors so that we don't
  # have to add it on the CLI here
  with_master_running_on master, "--storeconfigs --storeconfigs_backend puppetdb --reports=store,puppetdb --autosign true --manifest #{manifest_file}", :preserve_ssl => true do

      step "Run agents once to submit reports" do
        run_agent_on agents, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database


  # This is a little unfortunate.  We have no real great way of retrieving the
  # ids of reports for a specific node.  The only choice we really have for the
  # moment is to issue a query to puppetdb to get *all* of the reports in the
  # database.  From that, we can search through the results and find the most
  # recent one for a given node, and pluck the report ID from that.  Then
  # we can do another query to get all of the events for that report, and do
  # our validation against those results.

  # Query for all of the reports:
  result = on database, "curl -G -H 'Accept: application/json' http://localhost:8080/v2/reports"

  reports = JSON.parse(result.stdout)

  agents.each do |agent|
    # Find the report ID for this agent
    report = reports.find {|r| r["certname"] == agent.node_name }

    # Now query for all of the events in this report
    result = on database, "curl -G -H 'Accept: application/json' 'http://localhost:8080/v2/events' --data-urlencode 'report-id=#{report["id"]}'"
    events = JSON.parse(result.stdout)

    # This is a bit weird as well; all "skipped" resources during a puppet
    # run will end up having events generated for them.  However, during a
    # typical puppet run there are a bunch of "Schedule" resources that will
    # always show up as skipped.  Here we filter them out because they're
    # not really interesting for this test.
    events = events.reject {|x| x["resource-type"] == "Schedule" }

    assert_equal(1, events.length)

    event = events[0]

    assert_equal("Notify", event["resource-type"], "resource-type doesn't match!")
    assert_equal("hi", event["resource-title"], "resource-title doesn't match!")
    assert_equal("message", event["property"], "property doesn't match!")
    assert_equal("Hi #{agent.node_name}", event["new-value"], "new-value doesn't match!")
  end

end
