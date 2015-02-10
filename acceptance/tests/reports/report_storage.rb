require 'json'

test_name "basic validation of puppet report submission" do

  step "setup a test manifest for the master and perform agent runs" do
    manifest = <<-MANIFEST
      notify { "hi":
        message => "Hi ${::clientcert}"
      }
    MANIFEST

    run_agents_with_new_site_pp(master, manifest)
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  agents.each do |agent|
    # Query for all of the reports for this node:
    result = on database, %Q|curl -G http://localhost:8080/v4/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|

    reports = JSON.parse(result.stdout)

    # We are assuming we only care about the most recent report, and they should
    # be sorted by descending timestamps
    report = reports[0]

    # Now query for all of the events in this report
    result = on database, %Q|curl -G 'http://localhost:8080/v4/events' --data 'query=["=",%20"report",%20"#{report["hash"]}"]'|
    events = JSON.parse(result.stdout)

    # This is a bit weird as well; all "skipped" resources during a puppet
    # run will end up having events generated for them.  However, during a
    # typical puppet run there are a bunch of "Schedule" resources that will
    # always show up as skipped.  Here we filter them out because they're
    # not really interesting for this test.
    events = events.reject {|x| x["resource_type"] == "Schedule" }

    assert_equal(1, events.length)

    event = events[0]

    assert_equal("Notify", event["resource_type"], "resource_type doesn't match!")
    assert_equal("hi", event["resource_title"], "resource_title doesn't match!")
    assert_equal("message", event["property"], "property doesn't match!")
    assert_equal("Hi #{agent.node_name}", event["new_value"], "new_value doesn't match!")
  end

end
