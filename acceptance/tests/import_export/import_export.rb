test_name "storeconfigs export and import" do

  step "clear puppetdb database so that we can control exactly what we will eventually be exporting" do
    clear_and_restart_puppetdb(database)
  end

  step "run each agent once to populate the database" do
    # dbadapter, dblocation, storeconfigs_backend, routefile
    with_master_running_on master, "--autosign true", :preserve_ssl => true do
      hosts.each do |host|
        run_agent_on host, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
    end
  end

  export_file1 = "./puppetdb-export1.tar.gz"
  export_file2 = "./puppetdb-export2.tar.gz"

  step "export data from puppetdb" do
    on database, "puppetdb-export --outfile #{export_file1}"
    scp_from(database, export_file1, ".")
  end

  step "clear puppetdb database so that we can import into a clean db" do
    clear_and_restart_puppetdb(database)
  end

  step "import data into puppetdb" do
    on database, "puppetdb-import --infile #{export_file1}"
    sleep_until_queue_empty(database)
  end

  step "export data from puppetdb again" do
    on database, "puppetdb-export --outfile #{export_file2}"
    scp_from(database, export_file2, ".")
  end

  step "verify legacy export data matches new export data" do
    compare_export_data(export_file1, export_file2)
  end


  #
  #driver_legacy_export_file = "./legacy_storeconfigs_export.tar.gz"
  #driver_new_export_file    = "./puppetdb-export.tar.gz"
  #
  #step "export the storeconfigs data" do
  #  result = on master, "puppet storeconfigs export #{args}"
  #  regex = /Exported storeconfigs data to (.*)/
  #  assert_match regex, result.output
  #  legacy_export_file = regex.match(result.output)[1]
  #
  #  scp_from(master, legacy_export_file, ".")
  #  File.rename(File.join(".", File.basename(legacy_export_file)), driver_legacy_export_file)
  #end
  #
  #clear_and_restart_puppetdb(database)
  #
  #step "import data into puppetdb" do
  #  db_legacy_export_dir  = "."
  #  db_legacy_export_file = File.join(db_legacy_export_dir, "legacy_storeconfigs_export.tar.gz")
  #  scp_to(database, driver_legacy_export_file, db_legacy_export_dir)
  #  on database, "puppetdb-import --infile #{db_legacy_export_file}"
  #end
  #
  #step "export data from puppetdb" do
  #  db_new_export_file = "./puppetdb-export.tar.gz"
  #  on database, "puppetdb-export --outfile #{db_new_export_file}"
  #  scp_from(database, db_new_export_file, ".")
  #end
  #
  #step "verify legacy export data matches new export data" do
  #  compare_export_data(driver_legacy_export_file, driver_new_export_file)
  #end
  #
  #teardown do
  #  on master, "gem uninstall activerecord activesupport"
  #end
end
