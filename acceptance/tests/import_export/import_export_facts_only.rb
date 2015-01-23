test_name "export and import tools" do
  bin_loc = puppetdb_bin_dir(database)

  step "clear puppetdb database so that we can control exactly what we will eventually be exporting" do
    clear_and_restart_puppetdb(database)
  end

  def run_agents_without_persisting_catalogs(host, manifest, env_vars = {})

    manifest_path = create_remote_site_pp(host, manifest)
    with_puppet_running_on host, {
      'master' => {
        'storeconfigs' => 'false',
        'autosign' => 'true',
        'manifest' => manifest_path,
        'trusted_node_data' => 'true'
      },
      'main' => {
        'stringify_facts' => 'false'
      }} do
      #only some of the opts work on puppet_agent, acceptable exit codes does not
      agents.each do |agent|
        on agent,
        puppet_agent("--test --server #{host}", { 'ENV' => env_vars }),
        :acceptable_exit_codes => [0,2]
      end

    end
  end


  step "setup a test manifest for the master and perform agent runs" do
    manifest = <<-MANIFEST
      node default {
        @@notify { "exported_resource": }
        notify { "non_exported_resource": }
     }
    MANIFEST

    run_agents_without_persisting_catalogs(master, manifest, {"facter_foo" => "bar"})
  end

  step "verify foo fact present" do
    result = on master, "puppet facts find #{master.node_name} --terminus puppetdb"
    facts = parse_json_with_error(result.stdout.strip)
    assert_equal('bar', facts['values']['foo'], "Failed to retrieve fact 'foo' for '#{master.node_name}' via inventory service!")
  end

  step "verify trusted fact present" do
    result = on master, "puppet facts find #{master.node_name} --terminus puppetdb"
    facts = parse_json_with_error(result.stdout.strip)
    assert_equal("remote", facts['values']['trusted']["authenticated"],
                 "Failed to retrieve trusted facts for '#{master.node_name}' via inventory service!")
  end


  step "Verify that the number of active nodes is what we expect" do
    result = on database, %Q|curl -G http://localhost:8080/v4/nodes|
    result_node_statuses = parse_json_with_error(result.stdout)
    assert_equal(agents.length, result_node_statuses.length, "Should only have 1 node")

    node = result_node_statuses.first
    assert(node["catalog_timestamp"].nil?, "Should not have a catalog timestamp")
    assert(node["facts_timestamp"], "Should have a facts timestamp")
  end

  export_file1 = "./puppetdb-export1.tar.gz"
  export_file2 = "./puppetdb-export2.tar.gz"

  step "export data from puppetdb" do
    on database, "#{bin_loc}/puppetdb export --outfile #{export_file1}"
    scp_from(database, export_file1, ".")
  end

  step "clear puppetdb database so that we can import into a clean db" do
    clear_and_restart_puppetdb(database)
  end

  step "import data into puppetdb" do
    on database, "#{bin_loc}/puppetdb import --infile #{export_file1}"
    sleep_until_queue_empty(database)
  end

  step "verify facts were exported/imported correctly" do
    result = on master, "puppet facts find #{master.node_name} --terminus puppetdb"
    facts = parse_json_with_error(result.stdout.strip)
    assert_equal('bar', facts['values']['foo'],
                 "Failed to retrieve facts for '#{master.node_name}' via inventory service!")
    assert_equal("remote", facts['values']['trusted']["authenticated"],
                 "Failed to retrieve trusted facts for '#{master.node_name}' via inventory service!")
  end

  step "Verify that the number of active nodes is what we expect" do
    result = on database, %Q|curl -G http://localhost:8080/v4/nodes|
    result_node_statuses = parse_json_with_error(result.stdout)
    assert_equal(agents.length, result_node_statuses.length, "Should only have 1 node")

    node = result_node_statuses.first
    assert(node["catalog_timestamp"].nil?, "Should not have a catalog timestamp")
    assert(node["facts_timestamp"], "Should have a facts timestamp")
  end

end
