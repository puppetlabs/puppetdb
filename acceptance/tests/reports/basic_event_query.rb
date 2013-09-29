require 'json'
require 'time'
require 'cgi'

test_name "validation of basic PuppetDB resource event queries" do

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

  # NOTE: this implementation assumes that the test coordinator machine and
  # all of the SUTs are using NTP, and that their system date/times are roughly
  # in sync with one another.  If this causes problems we could loop over the
  # agents and build up a map of their timestamps, and then build our queries
  # based on those values.
  query_start_time = Time.now.iso8601


  # TODO: the module should be setting up the report processors so that we don't
  # have to add it on the CLI here
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
    step "Querying for Notify event on agent '#{agent.node_name}'" do

      # NOTE: this query is overly complex and has a few useless clauses
      # in it, which are only intended to exercise a larger subset of
      # the functionality of the query language.
      query = <<EOM
["and",
        ["=", "certname", "#{agent.node_name}"],
        ["=", "resource-type", "Notify"],
        ["not",
          ["=", "resource-title", "bunk"]],
        ["or",
          ["=", "status", "success"],
          ["=", "status", "booyah"]],
        ["~", "property", "^[Mm]essage$"],
        ["~", "message", "Hi #{agent.node_name}"],
        [">", "timestamp", "#{query_start_time}"]]
EOM
      query = CGI.escape(query)

      # Now query for all of the event for this agent
      result = on database, %Q|curl -G -H 'Accept: application/json' 'http://localhost:8080/experimental/events' --data 'query=#{query}'|
      events = JSON.parse(result.stdout)

      assert_equal(1, events.length, "Expected exactly one matching 'Notify' event for host '#{agent.node_name}'; found #{events.length}.")
    end
  end

end
