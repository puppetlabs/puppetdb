# We skip this step entirely unless we are running in :upgrade mode.
OLDEST_SUPPORTED_UPGRADE="4.0.0"

if ([:upgrade_oldest, :upgrade_latest].include? test_config[:install_type] and not test_config[:skip_presuite_provisioning])
  install_target = test_config[:install_type] == :upgrade_latest ? 'latest' : OLDEST_SUPPORTED_UPGRADE
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    databases.each do |database|
      enable_https_apt_sources(database)
      install_puppetdb(database, install_target)
      start_puppetdb(database)
      install_puppetdb_termini(master, databases, install_target)
    end
  end
end
