if (test_config[:install_mode] == :upgrade_oldest) \
    && !(test_config[:skip_presuite_provisioning])

  step "Clean out puppet6 repos to prepare for puppet7 upgrade" do
    # skip this step for bullseye beacause it only installs puppet7 in upgrade_oldest
    if test_config[:install_mode] == :upgrade_oldest
      databases.each do |database|

        # need to remove puppet6 repo to avoid conflicts when upgrading
        uninstall_package(database, "puppet7-release")

        # This is an upgrade oldest test, but we are upgrading now and want the
        # latest platform so specify the :install type to get the proper puppet repo
        #
        # this upgrades puppet-agent and puppetserver packages
        install_puppet(puppet_repo_version(test_config[:platform_version],
                                           :install,
                                           test_config[:nightly]))

        on(database, puppet('resource', 'host', 'updates.puppetlabs.com', 'ensure=present', "ip=127.0.0.1") )
      end
    end
  end
end
