test_name "Install Puppet" do
  unless (test_config[:skip_presuite_provisioning])
    step "Install Puppet" do
      hosts.each do |host|
        install_package(host, 'puppet-agent')
        on( host, puppet('resource', 'host', 'updates.puppetlabs.com', 'ensure=present', "ip=127.0.0.1") )
        install_package(host, 'puppetserver')
        install_puppet_conf(host)
      end
    end
  end

  step "Populate facts from each host" do
    populate_facts
  end
end
