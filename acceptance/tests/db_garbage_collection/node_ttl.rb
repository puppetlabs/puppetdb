require 'json'

test_name "validate that nodes are deactivated based on node-ttl setting" do

  with_master_running_on master, "--autosign true", :preserve_ssl => true do
    step "Run agents once to activate nodes" do
      run_agent_on agents, "--test --server #{master}"
    end
  end

  step "Verify that the number of active nodes is what we expect" do
    result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/nodes|
    result_node_names = JSON.parse(result.stdout)
    assert_equal(agents.length, result_node_names.length, "Expected query to return '#{agents.length}' active nodes; returned '#{result_node_names.length}'")
  end

  step "Sleep for one second to make sure we have a ttl to exceed" do
    sleep 1
  end

  step "Back up the database.ini file and create a temp one with a ttl" do
    on database, "cp /etc/puppetdb/conf.d/database.ini /etc/puppetdb/conf.d/database.ini.bak"
    # TODO: this could/should be done via the module once we support it
    on database, "echo 'node-ttl = 1s' >> /etc/puppetdb/conf.d/database.ini"
  end

  step "Restart PuppetDB to pick up config changes" do
    restart_puppetdb database
  end

  # TODO: this is really fragile, it would be better if there were some way
  # to tell that the GC had finished.  We could maybe scrape the logs if this
  # proves too fragile.
  step "sleep 5 seconds to allow GC to complete" do
    sleep 5
  end

  step "Verify that the number of active nodes is zero" do
    result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/nodes|
    result_node_names = JSON.parse(result.stdout)
    assert_equal(0, result_node_names.length, "Expected query to return '0' active nodes; returned '#{result_node_names.length}'")
  end

  step "Restore the original database.ini file and restart puppetdb" do
    on database, "mv /etc/puppetdb/conf.d/database.ini.bak /etc/puppetdb/conf.d/database.ini ; chown puppetdb:puppetdb /etc/puppetdb/conf.d/database.ini"
    restart_puppetdb database
  end

end

