test_name "Install Puppet" do
  unless (test_config[:skip_presuite_provisioning])
    step "Install Puppet" do
      install_puppet(puppet_repo_version)
    end
  end

  step "Populate facts from each host" do
    populate_facts
  end

  pidfile = '/var/run/puppet/master.pid'

  master_facts = facts(master.name)

  if options[:type] == 'aio' then
    bounce_service( master, master['puppetservice'], 10 )
  else
    with_puppet_running_on(
      master,
      :master => {:dns_alt_names => "puppet,#{master_facts['hostname']},#{master_facts['fqdn']}",
                  :trace => 'true'}) do
      # PID file exists?
      step "PID file created?" do
        on master, "[ -f #{pidfile} ]"
      end
    end
  end
end
