require 'json'
require 'time'
require 'cgi'

test_name "basic validation of puppet report query by timestamp" do

  Log.notify "Setting up manifest file"
  manifest = <<MANIFEST
notify { "hi":
  message => "Hi ${::clientcert}"
}
MANIFEST

  tmpdir = master.tmpdir('report_storage')

  manifest_file = File.join(tmpdir, 'site.pp')

  create_remote_file(master, manifest_file, manifest)

  on master, "chmod -R +rX #{tmpdir}"

  start_times = agents.inject({}) do |hash, agent|
    hash[agent.node_name] = current_time_on agent
    hash
  end

  agents.each do |agent|
    # Query for all of the events after the start time
    result = on database, %Q|curl -G http://localhost:8080/v3/events --data 'query=["and",%20["=",%20"certname",%20"#{agent.node_name}"],%20[">",%20"timestamp",%20"#{start_times[agent.node_name]}"]]'|

    # We expect no results (assuming all of the machines' timestamps are relatively sane),
    # because we haven't done any agent runs after the specified time.
    events = JSON.parse(result.stdout)
    assert_equal(0, events.length, "Expected no results from event query with timestamp more recent than last batch of agent runs")
  end

  # TODO: the module should be setting up the report processors so that we don't have to add it on the CLI here
  with_puppet_running_on master, {
    'master' => {
      'storeconfigs' => 'true',
      'storeconfigs_backend' => 'puppetdb',
      'autosign' => 'true',
      'manifest' => manifest_file
    }} do

      step "Run agents once to submit reports" do
        run_agent_on agents, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  agents.each do |agent|
    start_time = start_times[agent.node_name]

    # Query for all of the events after the start time
    result = on database, %Q|curl -G http://localhost:8080/v3/events --data 'query=["and",%20["=",%20"certname",%20"#{agent.node_name}"],%20[">",%20"timestamp",%20"#{start_time}"]]'|

    # This time, we do expect results because we've done agent runs more recently than the timestamp
    events = JSON.parse(result.stdout)
    assert(events.length > 0, "Expected results from event query with timestamp more recent than last batch of agent runs")

    # Now, just for good measure, we'll try a compound query with an end time greater
    # than the timestamp of the agent runs, and expect the results to match the
    # previous query.

    end_time = current_time_on agent
    result = on database, %Q|curl -G http://localhost:8080/v3/events --data 'query=["and",%20["=",%20"certname",%20"#{agent.node_name}"],%20["and",%20[">",%20"timestamp",%20"#{start_time}"],%20["<",%20"timestamp",%20"#{end_time}"]]]'|
    events2 = JSON.parse(result.stdout)
    assert(events.length == events2.length, "Expected compound event time query to return the same number of results as the previous query")
  end
end
