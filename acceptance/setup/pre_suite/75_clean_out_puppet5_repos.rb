if (test_config[:install_mode] == :upgrade_oldest) \
    && !(test_config[:skip_presuite_provisioning])

  step "Clean out puppet5 repos to prepare for puppet6 upgrade" do
    # skip this step for rhel8 beacause it only installs puppet6 in upgrade_oldest
    if test_config[:install_mode] == :upgrade_oldest && !is_rhel8
      databases.each do |database|

        # need to remove puppet5 repos to avoid conflicts when upgrading
        uninstall_package(database, "puppet5-release")
        uninstall_package(database, "puppet5-nightly-release")

        # init the puppet6 repos to allow it to find the puppet6 collection
        initialize_repo_on_host(database, test_config[:os_families][database.name], test_config[:nightly])

        # install puppet 6 directly to prepare for upgrade
        install_puppet_agent_on(database, {:puppet_collection => "puppet6"})
        on(database, puppet('resource', 'host', 'updates.puppetlabs.com', 'ensure=present', "ip=127.0.0.1") )
        install_package(database, 'puppetserver')
      end
    end
  end
end
