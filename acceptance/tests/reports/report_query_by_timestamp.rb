require 'json'
require 'time'
require 'cgi'

puppetdb_query_url = "http://localhost:8080/pdb/query"
test_name "basic validation of puppet report query by timestamp" do
  start_times = agents.inject({}) do |hash, agent|
    hash[agent.node_name] = current_time_on agent
    hash
  end

  agents.each do |agent|
    # Query for all of the events after the start time
    result = on database, %Q|curl -G #{puppetdb_query_url}/v4/events --data 'query=["and",%20["=",%20"certname",%20"#{agent.node_name}"],%20[">",%20"timestamp",%20"#{start_times[agent.node_name]}"]]'|

    # We expect no results (assuming all of the machines' timestamps are relatively sane),
    # because we haven't done any agent runs after the specified time.
    events = JSON.parse(result.stdout)
    assert_equal(0, events.length, "Expected no results from event query with timestamp more recent than last batch of agent runs")
  end

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
    start_time = start_times[agent.node_name]

    # Query for all of the events after the start time
    result = on database, %Q|curl -G #{puppetdb_query_url}/v4/events --data 'query=["and",%20["=",%20"certname",%20"#{agent.node_name}"],%20[">",%20"timestamp",%20"#{start_time}"]]'|

    # This time, we do expect results because we've done agent runs more recently than the timestamp
    events = JSON.parse(result.stdout)
    assert(events.length > 0, "Expected results from event query with timestamp more recent than last batch of agent runs")

    # Now, just for good measure, we'll try a compound query with an end time greater
    # than the timestamp of the agent runs, and expect the results to match the
    # previous query.

    end_time = current_time_on agent
    result = on database, %Q|curl -G #{puppetdb_query_url}/v4/events --data 'query=["and",%20["=",%20"certname",%20"#{agent.node_name}"],%20["and",%20[">",%20"timestamp",%20"#{start_time}"],%20["<",%20"timestamp",%20"#{end_time}"]]]'|
    events2 = JSON.parse(result.stdout)
    assert(events.length == events2.length, "Expected compound event time query to return the same number of results as the previous query")
  end
end
