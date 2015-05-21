require 'json'
require 'time'
require 'cgi'

puppetdb_query_url = "http://localhost:8080/pdb/query"
test_name "validation of basic PuppetDB resource event queries" do

  # NOTE: this implementation assumes that the test coordinator machine and
  # all of the SUTs are using NTP, and that their system date/times are roughly
  # in sync with one another.  If this causes problems we could loop over the
  # agents and build up a map of their timestamps, and then build our queries
  # based on those values.
  query_start_time = Time.now.iso8601

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
    step "Querying for Notify event on agent '#{agent.node_name}'" do

      # NOTE: this query is overly complex and has a few useless clauses
      # in it, which are only intended to exercise a larger subset of
      # the functionality of the query language.
      query = <<EOM
["and",
        ["=", "certname", "#{agent.node_name}"],
        ["=", "resource_type", "Notify"],
        ["not",
          ["=", "resource_title", "bunk"]],
        ["or",
          ["=", "status", "success"],
          ["=", "status", "booyah"]],
        ["~", "property", "^[Mm]essage$"],
        ["~", "message", "Hi #{agent.node_name}"],
        [">", "timestamp", "#{query_start_time}"]]
EOM
      query = CGI.escape(query)

      # Now query for all of the event for this agent
      result = on database, %Q|curl -G '#{puppetdb_query_url}/v4/events' --data 'query=#{query}'|
      events = JSON.parse(result.stdout)

      assert_equal(1, events.length, "Expected exactly one matching 'Notify' event for host '#{agent.node_name}'; found #{events.length}.")
    end
  end

end
