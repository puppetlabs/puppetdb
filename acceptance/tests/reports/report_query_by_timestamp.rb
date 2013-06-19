require 'json'
require 'time'
require 'cgi'

test_name "basic validation of puppet report query by timestamp" do

  query_start_time = CGI.escape(Time.now.iso8601)

  # Query for all of the events after the start time
  result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/events --data 'query=[">",%20"timestamp",%20"#{query_start_time}"]'|

  # We expect no results (assuming all of the machines' timestamps are relatively
  #  sane), because we haven't done any agent runs after the specified time.
  events = JSON.parse(result.stdout)
  assert_equal(0, events.length, "Expected no results from event query with timestamp more recent than last batch of agent runs")

  # TODO: the module should be setting up the report processors so that we don't
  # have to add it on the CLI here
  with_master_running_on master, "--storeconfigs --storeconfigs_backend puppetdb --reports=store,puppetdb --autosign true", :preserve_ssl => true do

      step "Run agents once to submit reports" do
        run_agent_on agents, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  # Query for all of the events after the start time
  result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/events --data 'query=[">",%20"timestamp",%20"#{query_start_time}"]'|

  # This time, we do expect results because we've done agent runs more recently
  # than the timestamp['
  events = JSON.parse(result.stdout)
  assert(events.length > 0, "Expected results from event query with timestamp more recent than last batch of agent runs")

  # Now, just for good measure, we'll try a compound query with an end time greater
  # than the timestamp of the agent runs, and expect the results to match the
  # previous query.

  end_time = CGI.escape(Time.now.iso8601)
  result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/events --data 'query=["and",%20[">",%20"timestamp",%20"#{query_start_time}"],%20["<",%20"timestamp",%20"#{end_time}"]]'|
  events2 = JSON.parse(result.stdout)
  assert(events.length == events2.length, "Expected compound event time query to return the same number of results as the previous query")

end
