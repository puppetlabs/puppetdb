require 'json'

test_name "validate report command queueing and puppetdb flush_queue face'" do

  report_counts = {}

  step "Count the existing reports for each node" do
    agents.each do |agent|
      # Query for all of the reports for this node:
      result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|

      reports = JSON.parse(result.stdout)
      report_counts[agent] = reports.length
    end
  end

  step "stop puppetdb so that commands will fail" do
    stop_puppetdb(database)
  end

  # once the module supports configuring reports, get rid of the reports cli arg
  with_master_running_on master, "--autosign true --reports=store,puppetdb", :preserve_ssl => true do
    step "Run agents once to generate queued reports" do
      run_agent_on agents, "--test --server #{master}"
    end
  end

  step "Verify that there are queued reports" do
    # This is not ideal; it's dependent on implementation details (perhaps there
    # could be a face command that would abstract out the process of determining
    # the queue size), and it hard-codes the path to the directory.
    num_queued_commands = on(master,
         "ls -l /var/lib/puppet/puppetdb/commands/*.command |wc -l").stdout.strip.to_i
    assert_equal(agents.length, num_queued_commands, "Expected to see '#{agents.length}' queued commands; saw '#{num_queued_commands}'")
  end

  step "Start puppetdb so that we can flush commands to it" do
    start_puppetdb(database)
  end

  step "Flush the queued commands" do
    on master, "puppet puppetdb flush_queue"
  end

  step "Count the existing reports for each node" do
    agents.each do |agent|
      # Query for all of the reports for this node:
      result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|

      reports = JSON.parse(result.stdout)
      assert_equal(reports.length, report_counts[agent] + 1, "Expected agent '#{agent}' to have #{report_counts[agent] + 1} reports; found #{reports.length}")
    end
  end


end

