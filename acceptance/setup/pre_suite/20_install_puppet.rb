test_name "Install Puppet" do
  step "Install Puppet" do
    install_puppet
  end

  result = on master, "facter -y"
  facts = YAML.load(result.stdout)
  pidfile = '/var/run/puppet/master.pid'

  with_puppet_running_on(master, {
    'master' => {
      'dns_alt_names' => "puppet,#{facts['hostname']},#{facts['fqdn']}",
      'verbose' => 'true',
    },
  }) do
    # PID file exists?
    step "PID file created?" do
      on master, "[ -f #{pidfile} ]"
    end
  end
end
