# We skip this step entirely unless we are running in :upgrade mode.
if (test_config[:install_mode] == :upgrade and not test_config[:skip_presuite_provisioning])
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    databases.each do |database|
      test_url='/v3/version'
      install_puppetdb(database, test_config[:database], 'latest', test_url)
      start_puppetdb(database, test_url)
      install_puppetdb_termini(master, database, 'latest', 'puppetdb-terminus', test_url)
    end
  end
end
