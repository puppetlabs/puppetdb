# We skip this step entirely unless we are running in :upgrade mode.
OLDEST_SUPPORTED_UPGRADE="2.3.8"

if ([:upgrade_oldest, :upgrade_latest].include? test_config[:install_mode] and not test_config[:skip_presuite_provisioning])
  install_target = test_config[:install_mode] == :upgrade_latest ? 'latest' : OLDEST_SUPPORTED_UPGRADE
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    databases.each do |database|
      install_puppetdb(database, test_config[:database], install_terget)
      start_puppetdb(database)
      install_puppetdb_termini(master, database, install_target)
    end
  end
end
