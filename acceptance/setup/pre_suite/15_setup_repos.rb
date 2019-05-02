unless (test_config[:skip_presuite_provisioning])
  step "Install Puppet Labs repositories" do

    # when running upgrade_oldest init puppet5 repos
    puppet_collection = test_config[:install_mode] == :upgrade_oldest ? :puppet5 : :puppet6

    hosts.each do |host|
      initialize_repo_on_host(host, test_config[:os_families][host.name], test_config[:nightly], puppet_collection)
    end
  end
end
