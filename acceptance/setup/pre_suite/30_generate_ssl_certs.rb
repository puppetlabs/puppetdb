unless (test_config[:skip_presuite_provisioning])
  step "Run an agent to create the SSL certs" do
    on( master, "chown -R puppet:puppet /opt/puppetlabs/puppet/cache")
    databases.each do |database|
      with_puppet_running_on(master, {'master' => {'autosign' => 'true', 'trace' => 'true'}}) do
        run_agent_on(database, "--test")
      end
    end
  end
end
