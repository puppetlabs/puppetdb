
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
end
