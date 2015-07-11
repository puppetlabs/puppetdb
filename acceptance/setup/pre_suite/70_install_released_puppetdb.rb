# We skip this step entirely unless we are running in :upgrade mode.
if (test_config[:install_mode] == :upgrade)
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    last_released_verison = `git describe --abbrev=0`.chomp
    install_puppetdb(database, test_config[:database], last_released_verison)
    start_puppetdb(database)
    install_puppetdb_termini(master, database, last_released_verison)
  end
end
