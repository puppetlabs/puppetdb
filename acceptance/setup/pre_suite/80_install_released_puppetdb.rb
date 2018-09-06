# We skip this step entirely unless we are running in :upgrade mode.
# or if we're running against bionic on the 5.1.x branch
version = test_config[:package_build_version].to_s
latest_released = get_latest_released(version)

if ([:upgrade_oldest, :upgrade_latest].include? test_config[:install_mode] \
    and not test_config[:skip_presuite_provisioning] \
    and not (is_bionic and get_testing_branch(version) == '5.1.x'))
  install_target = test_config[:install_mode] == :upgrade_latest ? latest_released : oldest_supported
  step "Install most recent released PuppetDB on the PuppetDB server for upgrade test" do
    databases.each do |database|
      enable_https_apt_sources(database)
      install_puppetdb(database, install_target)
      start_puppetdb(database)
      install_puppetdb_termini(master, databases, install_target)
    end
  end
end
