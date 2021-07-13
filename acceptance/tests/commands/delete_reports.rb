test_name "test puppetdb delete" do
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

  step "Verify the data was stored in PuppetDB" do
    check_record_count("nodes", agents.length)
    check_record_count("factsets", agents.length)
    check_record_count("catalogs", agents.length)
    check_record_count("reports", agents.length)
  end

  step "Run puppetdb delete-reports" do
    bin_loc = puppetdb_bin_dir(database)
    on(database, "#{bin_loc}/puppetdb delete-reports")
  end

  step "Verify puppetdb can still be queried" do
    check_record_count("nodes", agents.length)
  end

  step "Verify puppetdb has no reports" do
    check_record_count("reports", 0)
  end
end
