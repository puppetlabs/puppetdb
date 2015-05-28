step "Install development build of PuppetDB on the PuppetDB server" do
  os = test_config[:os_families][database.name]

  case test_config[:install_type]
  when :git
    raise "No PUPPETDB_REPO_PUPPETDB set" unless test_config[:repo_puppetdb]

    case os
    when :redhat, :fedora
      on database, "yum install -y git-core ruby rubygem-rake"
    when :debian
      on database, "apt-get install -y git-core ruby rake"
    else
      raise "OS #{os} not supported"
    end

    on database, "rm -rf #{GitReposDir}/puppetdb"
    repo = extract_repo_info_from(test_config[:repo_puppetdb].to_s)
    install_from_git database, GitReposDir, repo,
      :refspec => '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*'

    if (test_config[:database] == :postgres)
      install_postgres(database)
    end
    install_puppetdb_via_rake(database)
    start_puppetdb(database)
    install_puppetdb_termini_via_rake(master, database)
  when :package
    Log.notify("Installing puppetdb from package; install mode: '#{test_config[:install_mode].inspect}'")

    install_puppetdb(database, test_config[:database])

    if test_config[:validate_package_version]
      validate_package_version(database)
    end

    install_puppetdb_termini(master, database)

    # The package should automatically start the service on debian.  On redhat,
    # it doesn't.  However, during test runs where we're doing package upgrades,
    # the redhat package *should* detect that the service was running before the
    # upgrade, and it should restart it automatically.
    #
    # That leaves the case where we're on a redhat box and we're running the
    # tests as :install only (as opposed to :upgrade).  In that case we need
    # to start the service ourselves here.
    if test_config[:install_mode] == :install and [:redhat, :fedora].include?(os)
      start_puppetdb(database)
    else
      # make sure it got started by the package install/upgrade
      sleep_until_started(database)
    end

  end
end
