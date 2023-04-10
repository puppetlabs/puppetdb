step "Install development build of PuppetDB on the PuppetDB server" do
  databases.each do |database|
    os = test_config[:os_families][database.name]

    case test_config[:install_type]
    when :git
      Log.notify("Install puppetdb from source")
      Log.error database

      enable_https_apt_sources(database)
      install_postgres(database)
      install_puppetdb_via_rake(database)
      start_puppetdb(database)
    when :package
      Log.notify("Installing puppetdb from package; install mode: '#{test_config[:install_mode].inspect}'")

      enable_https_apt_sources(database)
      install_puppetdb(database)

      if test_config[:validate_package_version]
        validate_package_version(database)
      end

      # The package should automatically start the service on debian.  On redhat,
      # it doesn't.  However, during test runs where we're doing package upgrades,
      # the redhat package *should* detect that the service was running before the
      # upgrade, and it should restart it automatically.
      #
      # That leaves the case where we're on a redhat box and we're running the
      # tests as :install only (as opposed to :upgrade).  In that case we need
      # to start the service ourselves here.
      if test_config[:install_mode] == :install and [:redhat].include?(os)
        start_puppetdb(database)
      else
        # make sure it got started by the package install/upgrade
        sleep_until_started(database)
      end

    end
  end

  case test_config[:install_type]
  when :git
    install_puppetdb_termini_via_rake(master, databases)
  when :package
    install_puppetdb_termini(master, databases)
  end
end
