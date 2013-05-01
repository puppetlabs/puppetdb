test_name "anonymize tool" do
  sbin_loc = puppetdb_sbin_dir(database)

  step "clear puppetdb database so that we can control exactly what we will eventually be exporting" do
    clear_and_restart_puppetdb(database)
  end

  step "run each agent once to populate the database" do
    # dbadapter, dblocation, storeconfigs_backend, routefile
    with_puppet_running_on master, {
      'master' => {
        'autosign' => 'true',
        'report' => 'true',
      }} do

      hosts.each do |host|
        run_agent_on host, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
    end
  end

  export_file1 = "./puppetdb-export1.tar.gz"
  anon_file = "./puppetdb-anon.tar.gz"
  export_file2 = "./puppetdb-export2.tar.gz"

  step "export data from puppetdb" do
    on database, "#{sbin_loc}/puppetdb-export --outfile #{export_file1}"
    scp_from(database, export_file1, ".")
  end

  step "clear puppetdb database so that we can import into a clean db" do
    clear_and_restart_puppetdb(database)
  end

  step "anonymize the data with profile 'none'" do
    on database, "#{sbin_loc}/puppetdb-anonymize --infile #{export_file1} --outfile #{anon_file} --profile none"
    scp_from(database, anon_file, ".")
  end

  step "verify original export data matches anonymized data" do
    compare_export_data(export_file1, anon_file)
  end

  step "import data into puppetdb" do
    on database, "#{sbin_loc}/puppetdb-import --infile #{anon_file}"
    sleep_until_queue_empty(database)
  end

  step "export data from puppetdb again" do
    on database, "#{sbin_loc}/puppetdb-export --outfile #{export_file2}"
    scp_from(database, export_file2, ".")
  end

  step "verify original export data matches new export data" do
    compare_export_data(export_file1, export_file2)
  end
end
