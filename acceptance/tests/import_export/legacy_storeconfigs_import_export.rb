test_name "storeconfigs export and import" do

  confine :except, :platform => 'ubuntu-10.04-amd64'

  skip_test "Skipping test for PE because sqlite3 isn't available" if master.is_pe?

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

    manifest_path = create_remote_site_pp(master, manifest)

    on master, "chown puppet:puppet #{db_path}"
    on master, "chmod -R 777 #{db_path}"
  end

  step "run each agent once to populate the database" do
    # dbadapter, dblocation, storeconfigs_backend, routefile
    with_puppet_running_on master, {
      'master' => {
        'dbadapter' => 'sqlite3',
        'dblocation' => db_path,
        'storeconfigs_backend' => 'active_record',
        'debug' => 'true',
        'autosign' => 'true'
      },
      'main' => {
        'environmentpath' => manifest_path,
      }
    } do
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
    on database, "puppetdb import --infile #{db_legacy_export_file}"
  end

  step "Verify imported catalogs" do
    hosts.each do |host|
      result = on database, %Q|curl -G http://localhost:8080/v4/catalogs/#{host.node_name}|
      result_catalog = JSON.parse(result.stdout)
      assert_equal(host.node_name, result_catalog['name'], "Catalog for node #{host.node_name} not found")
    end
  end
end
