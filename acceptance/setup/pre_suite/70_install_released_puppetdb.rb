test_config = PuppetDBExtensions.config

# We skip this step entirely unless we are running in :upgrade mode.
if (test_config[:install_mode] == :upgrade)
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    install_puppetdb(database, test_config[:database])
    start_puppetdb(database)
    install_puppetdb_termini(master, database)
  end
end
