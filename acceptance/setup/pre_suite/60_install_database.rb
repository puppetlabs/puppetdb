test_config = PuppetDBExtensions.config

def install_postgres(test_config)
  step "Install postgres on the PuppetDB server" do
    manifest_path = database.tmpfile("puppetdb_postgres_manifest.pp")
    # TODO: use more stuff from the puppetdb module once it is robust enough
    #  to handle what we need.
    manifest_content = <<-EOS

  class { '::postgresql::server':
    config_hash => {
        'ip_mask_allow_all_users' => '0.0.0.0/0',
        'listen_addresses' => '*',
        'manage_redhat_firewall' => false,

        'ip_mask_deny_postgres_user' => '0.0.0.0/32',
        'postgres_password' => 'puppet',
    },
  }

  postgresql::db{ 'puppetdb':
    user          => 'puppetdb',
    password      => 'puppetdb',
    grant         => 'all',
  }
EOS

    create_remote_file(database, manifest_path, manifest_content)
    on database, puppet_apply("--modulepath #{test_config[:db_module_path]} #{manifest_path}")
  end
end

step "Install database" do
  Log.notify("Database to use: '#{test_config[:database]}'")
  case test_config[:database]
  when :postgres
    install_postgres(test_config)
  when :embedded
    Log.notify("Nothing to do.  It's embedded :)")
  else
    raise ArgumentError, "Unsupported database: '#{test_config[:database]}'"
  end
end
