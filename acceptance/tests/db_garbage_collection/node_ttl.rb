require 'json'

def restart_to_gc(database)
  step "Restart PuppetDB to pick up config changes" do
    restart_puppetdb database
  end

  # TODO: this is really fragile, it would be better if there were some way
  # to tell that the GC had finished.  We could maybe scrape the logs if this
  # proves too fragile.
  step "sleep 5 seconds to allow GC to complete" do
    sleep 5
  end
end

test_name "validate that nodes are deactivated and deleted based on ttl settings" do

  with_puppet_running_on master, {
    'master' => {
      'autosign' => 'true'
    }} do
    step "Run agents once to activate nodes" do
      run_agent_on agents, "--test --server #{master}"
    end
  end

  step "Verify that the number of active nodes is what we expect" do
    result = on database, %Q|curl -G http://localhost:8080/v3/nodes|
    result_node_statuses = JSON.parse(result.stdout)
    assert_equal(agents.length, result_node_statuses.length, "Expected query to return '#{agents.length}' active nodes; returned '#{result_node_statuses.length}'")
  end

  step "Sleep for one second to make sure we have a ttl to exceed" do
    sleep 1
  end

  step "Back up the database.ini file and create a temp one with a node-purge-ttl" do
    on database, "cp -p #{puppetdb_confdir(database)}/conf.d/database.ini #{puppetdb_confdir(database)}/conf.d/database.ini.bak"
    # TODO: this could/should be done via the module once we support it
    on database, "echo 'node-purge-ttl = 1s' >> #{puppetdb_confdir(database)}/conf.d/database.ini"
  end

  restart_to_gc database

  step "Verify that the nodes are still there and still active" do
    result = on database, %Q|curl -G http://localhost:8080/v3/nodes|
    result_node_statuses = JSON.parse(result.stdout)
    assert_equal(agents.length, result_node_statuses.length, "Expected query to return '#{agents.length}' active nodes; returned '#{result_node_statuses.length}'")
  end

  step "Restore the original database.ini and add a node-ttl" do
    on database, "cp -p #{puppetdb_confdir(database)}/conf.d/database.ini.bak #{puppetdb_confdir(database)}/conf.d/database.ini"
    on database, "echo 'node-ttl = 1s' >> #{puppetdb_confdir(database)}/conf.d/database.ini"
  end

  restart_to_gc database

  step "Verify that the nodes were deactivated but not deleted" do
    result = on database, %Q|curl -G http://localhost:8080/v3/nodes|
    result_node_statuses = JSON.parse(result.stdout)
    assert_equal(0, result_node_statuses.length, "Expected query to return '0' active nodes; returned '#{result_node_statuses.length}'")

    agents.each do |agent|
      result = on database, %Q|curl -G http://localhost:8080/v3/nodes/#{agent.node_name}|
      result_node_status = JSON.parse(result.stdout)

      assert_equal(agent.node_name, result_node_status['name'], "Didn't get a node back for #{agent.node_name}")
      assert_not_nil(result_node_status['deactivated'], "Expected #{agent.node_name} to be present but deactivated, and it wasn't deactivated")
    end
  end

  step "Sleep for one second to make sure we have a ttl to exceed" do
    sleep 1
  end

  step "Add a purge ttl to the database.ini file" do
    on database, "echo 'node-purge-ttl = 1s' >> #{puppetdb_confdir(database)}/conf.d/database.ini"
  end

  # In case there are any catalogs/facts/reports waiting to be processed, let
  # them finish so we can be sure everything was deleted.
  sleep_until_queue_empty database

  restart_to_gc database

  step "Verify that the nodes were all deleted" do
    agents.each do |agent|
      result = on database, %Q|curl -G http://localhost:8080/v3/nodes/#{agent.node_name}|
      result_node_status = JSON.parse(result.stdout)

      assert_equal({"error" => "No information is known about #{agent.node_name}"}, result_node_status, "Got a result back for #{agent.node_name} when it shouldn't exist")
    end
  end

  step "Verify that all associated data was deleted" do
    result = on database, "curl -G http://localhost:8080/v3/facts/operatingsystem"
    facts = JSON.parse(result.stdout)

    assert_equal([], facts, "Got facts when they should all have been deleted")

    # We have to supply a query for resources, so use one that will always match
    result = on database, %q|curl -G http://localhost:8080/v3/resources --data 'query=["=","exported",false]'|
    resources = JSON.parse(result.stdout)

    assert_equal([], resources, "Got resources when they should all have been deleted")

    # Reports can only be retrieved per node, so check one at a time.
    agents.each do |agent|
      result = on database, %Q|curl -G http://localhost:8080/experimental/reports --data 'query=["=","certname","#{agent.node_name}"]'|
      reports = JSON.parse(result.stdout)

      assert_equal([], resources, "Got reports for #{agent.node_name} when they should all have been deleted")
    end
  end

  step "Restore the original database.ini file and restart puppetdb" do
    on database, "mv #{puppetdb_confdir(database)}/conf.d/database.ini.bak #{puppetdb_confdir(database)}/conf.d/database.ini"
    restart_puppetdb database
  end

end

