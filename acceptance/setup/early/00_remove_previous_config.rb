
unless (options[:vmrun]) or (ENV['PUPPETDB_SKIP_PRESUITE_PROVISIONING'])
  step "Clean up configuration files on master" do
    on master, "rm -rf /etc/puppet/routes.yaml"
  end

  step "Clean up configuration files on puppetdb server" do
    on databases, "rm -rf /etc/puppetdb/ssl"
  end

  step "Remove old modules from master" do
    on master, "rm -rf /etc/puppet/modules/*"
  end

  step "drop in a correct /etc/hosts, which can be wrong on some hosts with virtualbox and DHCP" do
      hosts.each do |host|
	short_host_name = host.name.split('.').first
	hosts_contents = <<-EOS
127.0.0.1  #{host.name} #{short_host_name} localhost localhost.localdomain localhost4 localhost4.localdomain4
::1        localhost localhost.localdomain localhost6 localhost6.localdomain6
EOS
        on host, "echo '#{hosts_contents}' > /etc/hosts"
      end
  end
end
