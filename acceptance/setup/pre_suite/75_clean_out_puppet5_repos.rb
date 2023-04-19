if (test_config[:install_mode] == :upgrade_oldest) \
    && !(test_config[:skip_presuite_provisioning])

  step "Clean out puppet6 repos to prepare for puppet7 upgrade" do
    # skip this step for bullseye beacause it only installs puppet7 in upgrade_oldest
    if test_config[:install_mode] == :upgrade_oldest
      databases.each do |database|

        # need to remove puppet6 repo to avoid conflicts when upgrading
        uninstall_package(database, "puppet7-release")

        databases.each do |host|
          os = test_config[:os_families][host.name]

          # On RedHat systems, java 8 is still higher priority than java 8
          # To avoid the upgrade failing, we "pre-upgrade" the server to java 11
          if os == :redhat
            install_package(host, 'java-11-openjdk-headless')
            on(database, "alternatives --set java /usr/lib/jvm/java-11-openjdk-*/bin/java")
          end
        end

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
