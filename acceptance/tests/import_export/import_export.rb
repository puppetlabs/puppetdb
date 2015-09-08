test_name "export and import tools" do
  bin_loc = puppetdb_bin_dir(database)

  step "clear puppetdb database so that we can control exactly what we will eventually be exporting" do
    clear_and_restart_puppetdb(database)
  end

  step "setup a test manifest for the master and perform agent runs" do
    manifest = <<-MANIFEST
      node default {
        @@notify { "exported_resource": }
        notify { "non_exported_resource": }
     }
    MANIFEST

    run_agents_with_new_site_pp(master, manifest, {"facter_foo" => "bar"})
  end

  step "verify foo fact present" do
    result = on master, "puppet facts find #{master.node_name} --terminus puppetdb"
    facts = JSON.parse(result.stdout.strip)
    assert_equal('bar', facts['values']['foo'], "Failed to retrieve facts for '#{master.node_name}' via inventory service!")
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
    facts = JSON.parse(result.stdout.strip)
    assert_equal('bar', facts['values']['foo'], "Failed to retrieve facts for '#{master.node_name}' via inventory service!")
  end

  step "export data from puppetdb again" do
    on database, "#{bin_loc}/puppetdb export --outfile #{export_file2}"
    scp_from(database, export_file2, ".")
  end

  step "verify original export data matches new export data" do
    compare_export_data(export_file1, export_file2)
  end

  step "clear puppetdb database so that we can import into a clean db" do
    clear_and_restart_puppetdb(database)
  end

  step "import data into puppetdb with specific port and host" do
    on database, "#{bin_loc}/puppetdb import -p 8080 -H localhost --infile #{export_file1}"
    sleep_until_queue_empty(database)
  end

  step "export data from puppetdb again with specific port and host" do
    on database, "#{bin_loc}/puppetdb export -p 8080 -H localhost --outfile #{export_file2}"
    scp_from(database, export_file2, ".")
  end

  step "verify original export data matches new export data" do
    compare_export_data(export_file1, export_file2)
  end
end
