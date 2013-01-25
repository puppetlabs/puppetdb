require 'json'

test_name "facts should be available through facts terminus" do

  with_master_running_on master, "--autosign true", :preserve_ssl => true do

    step "Run agent once to populate database" do
      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => [0,2]
    end

    # Wait until all the commands have been processed
    sleep_until_queue_empty database

    step "Run facts face to find facts for each node" do

      hosts.each do |host|
        result = on master, "puppet facts find #{host.node_name} --terminus puppetdb"
        facts = JSON.parse(result.stdout.strip)
        assert_equal(host.node_name, facts['name'], "Failed to retrieve facts for '#{host.node_name}' via inventory service!")

      end
    end
  end
end
