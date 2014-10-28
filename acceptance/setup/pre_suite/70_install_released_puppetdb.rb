# We skip this step entirely unless we are running in :upgrade mode.
if (test_config[:install_mode] == :upgrade)
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    databases.each do |database|
      install_puppetdb(database, test_config[:database], 'latest')
      start_puppetdb(database)
      install_puppetdb_termini(master, database, 'latest')
    end
  end
end
