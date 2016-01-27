# We skip this step entirely unless we are running in :upgrade mode.
OLDEST_SUPPORTED_UPGRADE="2.3.8"

if ([:upgrade_oldest, :upgrade_latest].include? test_config[:install_mode] and not test_config[:skip_presuite_provisioning])
  if test_config[:install_mode] == :upgrade_latest
    install_target = 'latest'
  else
    install_target = OLDEST_SUPPORTED_UPGRADE
  end
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    databases.each do |database|
      install_puppetdb(database, install_target)
      start_puppetdb(database)
    end
    install_puppetdb_termini(master, databases, install_target)
    databases.each do |database|
      stop_puppetdb(database)
    end
  end
end
