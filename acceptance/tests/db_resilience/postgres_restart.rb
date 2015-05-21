if (test_config[:database] == :postgres)
  puppetdb_query_url = "http://localhost:8080/pdb/query"
  test_name "test postgresql database restart handling to ensure we recover from a restart" do
    step "clear puppetdb database" do
      clear_and_restart_puppetdb(database)
    end

    with_puppet_running_on master, {
      'master' => {
        'autosign' => 'true'
      }} do
      step "Run agents once to activate nodes" do
        run_agent_on agents, "--test --server #{master}"
      end
    end

    step "Verify that the number of active nodes is what we expect" do
      result = on database, %Q|curl -G #{puppetdb_query_url}/v4/nodes|
      result_node_statuses = JSON.parse(result.stdout)
      assert_equal(agents.length, result_node_statuses.length, "Expected query to return '#{agents.length}' active nodes; returned '#{result_node_statuses.length}'")
    end

    restart_postgres(database)

    step "Verify that the number of active nodes is what we expect" do
      result = on database, %Q|curl -G #{puppetdb_query_url}/v4/nodes|
      result_node_statuses = JSON.parse(result.stdout)
      assert_equal(agents.length, result_node_statuses.length, "Expected query to return '#{agents.length}' active nodes; returned '#{result_node_statuses.length}'")
    end
  end
end
