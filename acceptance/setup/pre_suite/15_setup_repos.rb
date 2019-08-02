unless (test_config[:skip_presuite_provisioning])
  step "Install Puppet Labs repositories" do

    hosts.each do |host|
      initialize_repo_on_host(host, test_config[:os_families][host.name], test_config[:nightly], puppet_repo_version)
    end
  end
end
