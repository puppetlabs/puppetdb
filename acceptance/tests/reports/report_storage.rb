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


  agents.each do |agent|
    # Query for all of the reports for this node:
    result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|

    reports = JSON.parse(result.stdout)

    # We are assuming we only care about the most recent report, and they should
    # be sorted by descending timestamps
    report = reports[0]

    # Now query for all of the events in this report
    result = on database, %Q|curl -G -H 'Accept: application/json' 'http://localhost:8080/experimental/events' --data 'query=["=",%20"report",%20"#{report["hash"]}"]'|
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
