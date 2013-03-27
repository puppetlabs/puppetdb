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
    on master, "chmod ugo+r #{manifest_path}"
    #on master, "mkdir -p #{db_path}"
    on master, "chown puppet:puppet #{db_path}"
    on master, "chmod -R 777 #{db_path}"
  end

  step "install activerecord on the master" do
    # EL5 doesn't have the activerecord gem, because it's Ruby 1.8.5. So we
    # have to use our own package of it.
    if master['platform'].include? 'el-5'
      on master, "yum install -y rubygem-activerecord"
    else
      on master, "gem install activerecord -v 2.3.17 --no-ri --no-rdoc"
    end
  end

  step "run each agent once to populate the database" do
    # dbadapter, dblocation, storeconfigs_backend, routefile
    with_master_running_on master, "#{args} --manifest #{manifest_path} --autosign true", :preserve_ssl => true do
      hosts.each do |host|
        run_agent_on host, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
    end
  end

  driver_legacy_export_file = "./legacy_storeconfigs_export.tar.gz"
  driver_new_export_file    = "./puppetdb-export.tar.gz"

  step "export the storeconfigs data" do
    result = on master, "puppet storeconfigs export #{args}"
    regex = /Exported storeconfigs data to (.*)/
    assert_match regex, result.output
    legacy_export_file = regex.match(result.output)[1]

    scp_from(master, legacy_export_file, ".")
    File.rename(File.join(".", File.basename(legacy_export_file)), driver_legacy_export_file)
  end

  clear_and_restart_puppetdb(database)

  step "import data into puppetdb" do
    db_legacy_export_dir  = "."
    db_legacy_export_file = File.join(db_legacy_export_dir, "legacy_storeconfigs_export.tar.gz")
    scp_to(database, driver_legacy_export_file, db_legacy_export_dir)
    on database, "puppetdb-import --infile #{db_legacy_export_file}"
  end

  step "export data from puppetdb" do
    db_new_export_file = "./puppetdb-export.tar.gz"
    on database, "puppetdb-export --outfile #{db_new_export_file}"
    scp_from(database, db_new_export_file, ".")
  end

  step "verify legacy export data matches new export data" do
    compare_export_data(driver_legacy_export_file, driver_new_export_file)
  end

  teardown do
    if master['platform'.include? 'el-5'
      on master, "yum -y remove rubygem-activerecord rubygem-activesupport"
    else
      on master, "gem uninstall activerecord activesupport"
    end
  end
end
