if (test_config[:install_mode] == :upgrade_oldest) \
    && !(test_config[:skip_presuite_provisioning])

  step "Clean out puppet5 repos to prepare for puppet6 upgrade" do
    # skip this step for bullseye beacause it only installs puppet7 in upgrade_oldest
    if test_config[:install_mode] == :upgrade_oldest && !(is_bullseye)
      databases.each do |database|

        # need to remove puppet6 repos to avoid conflicts when upgrading
        uninstall_package(database, "puppet6-release")
        uninstall_package(database, "puppet6-nightly-release")

        initialize_repo_on_host(database, test_config[:os_families][database.name], test_config[:nightly])
        install_puppet_agent_on(database, {:puppet_collection => "puppet7"})

        on(database, puppet('resource', 'host', 'updates.puppetlabs.com', 'ensure=present', "ip=127.0.0.1") )
        if get_os_family(database) == :debian
          database.install_package('puppetserver', '-o Dpkg::Options::="--force-confnew"')
        else
          install_package(database, 'puppetserver')
        end
      end
    end
  end
end
