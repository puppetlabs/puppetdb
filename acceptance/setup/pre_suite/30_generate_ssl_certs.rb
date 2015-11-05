unless (test_config[:skip_presuite_provisioning])
  step "Run an agent to create the SSL certs" do
    on( master, "chown -R puppet:puppet /opt/puppetlabs/puppet/cache")
    puppetserver_initialize_ssl
  end
end
