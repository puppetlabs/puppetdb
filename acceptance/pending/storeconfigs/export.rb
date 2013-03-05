test_name "storeconfigs export and import" do
  db_path = master.tmpfile('storeconfigs.sqlite3')
  manifest_path = master.tmpfile('storeconfigs.pp')
  args = "--dbadapter sqlite3 --dblocation #{db_path} --storeconfigs_backend active_record --debug"

  step "setup a test manifest for the master" do
    manifest = <<-MANIFEST
    node default {
      @@notify { "exported_resource": }
      notify { "non_exported_resource": }
    }
    MANIFEST

    create_remote_file master, manifest_path, manifest
  end

  step "run each agent once to populate the database" do
    # dbadapter, dblocation, storeconfigs_backend, routefile
    with_master_running_on master, "#{args} --manifest #{manifest_path} --autosign true", :preserve_ssl => true do
      hosts.each do |host|
        run_agent_on host, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
    end
  end

  step "export the storeconfigs data" do
    on master, "puppet storeconfigs export #{args}" do |result|
      regex = /Exported storeconfigs data to (.*)/
      assert_match regex, result.output

      filename = regex.match(result.output)[1]
    end
  end

  #step "import data into puppetdb"
  #step "verify correct data in puppetdb"
end
