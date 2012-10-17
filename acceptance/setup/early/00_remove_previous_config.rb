
unless (options[:vmrun])
  step "Clean up configuration files on master" do
    on master, "rm -rf /etc/puppet/routes.yaml"
  end

  step "Clean up configuration files on puppetdb server" do
    on database, "rm -rf /etc/puppetdb/ssl"
  end

  step "Remove old modules from master" do
    on master, "rm -rf /etc/puppet/modules/*"
  end
end
