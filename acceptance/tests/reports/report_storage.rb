require 'json'

test_name "basic validation of puppet report submission" do

  manifest = <<-MANIFEST
      notify { "hi":
        message => "Hi ${::clientcert}"
      }
  MANIFEST

  step "setup a test manifest for the master and perform agent runs" do
    run_agents_with_new_site_pp(master, manifest, {}, "--noop")
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  agents.each do |agent|
    # Query for all of the reports for this node:
    result = on database, %Q|curl -G http://localhost:8080/v4/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]' --data 'order_by=[{"field":"receive_time","order":"desc"}]'|

    reports = JSON.parse(result.stdout)

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

    assert_equal(true, report["noop"], "noop does not match!")
    assert_equal("Notify", event["resource_type"], "resource_type doesn't match!")
    assert_equal("hi", event["resource_title"], "resource_title doesn't match!")
    assert_equal("message", event["property"], "property doesn't match!")
    assert_equal("Hi #{agent.node_name}", event["new_value"], "new_value doesn't match!")
  end

  step "do a run without noop" do
    run_agents_with_new_site_pp(master, manifest)
    sleep_until_queue_empty database
  end

  agents.each do |agent|

    result = on database, %Q|curl -G http://localhost:8080/v4/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]' --data 'order_by=[{"field":"receive_time","order":"desc"}]'|
    reports = JSON.parse(result.stdout)
    report = reports[0]
    metrics = report["metrics"]

    step "ensure that noop is false for #{agent}" do
      assert_equal(false, report["noop"], "noop does not match!")
    end

    step "ensure that metrics check out for #{agent}" do
      total_events = metrics.detect {|m| m["name"] == "total" && m["category"] == "events"}
      total_changes = metrics.detect {|m| m["name"] == "total" && m["category"] == "changes"}
      resources_changed = metrics.detect {|m| m["name"] == "changed" && m["category"] == "resources"}
      assert(total_events["value"] == 1, "metrics do not match")
      assert(total_changes["value"] == 1, "metrics do not match")
      assert(total_changes["value"] == 1, "metrics do not match")
    end
  end
end
