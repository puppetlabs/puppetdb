test_name "puppetdb-ssl-setup" do
  confd = "#{puppetdb_confdir(database)}/conf.d"
  sbin_loc = "#{puppetdb_sbin_dir(database)}"

  step "backup jetty.ini and setup template" do
    on database, "cp #{confd}/jetty.ini #{confd}/jetty.ini.bak.ssl_setup_tests"
  end

  # We want to make sure the puppetdb-ssl-setup command behaves properly during
  # an upgrade, versus a clean installation. However, the source based
  # installation during upgrade wipes out the jetty.ini, so the tests are
  # slightly different here.
  #
  # This test will fail when we release our next version of PuppetDB with the
  # updated puppetdb-ssl-setup script (1.4.0 probably).
  test_config = PuppetDBExtensions.config
  if (test_config[:install_mode] == :upgrade) && (test_config[:install_type] == :package)
    step "check to make sure all legacy settings are configured for jetty.ini" do
      ["key-password", "keystore", "trust-password", "truststore"].each do |k|
        on database, "grep -e '^#{k} = ' #{confd}/jetty.ini"
      end
    end

    step "run puppetdb-ssl-setup again to make sure it is idempotent" do
      on database, "#{sbin_loc}/puppetdb-ssl-setup", :acceptable_exit_codes => [1]
      on database, "diff #{confd}/jetty.ini #{confd}/jetty.ini.bak.ssl_setup_tests"
    end

    step "run puppetdb-ssl-setup -f and check if it changes jetty.ini to use the new settings" do
      on database, "#{sbin_loc}/puppetdb-ssl-setup -f"
      ["ssl-host", "ssl-port", "ssl-key", "ssl-cert", "ssl-ca-cert"].each do |k|
        on database, "grep -e '^#{k} = ' #{confd}/jetty.ini"
      end

      ["key-password", "keystore", "trust-password", "truststore"].each do |k|
        on database, "grep -e '^#{k} = ' #{confd}/jetty.ini", :acceptable_exit_codes => [1]
      end
    end
  else
    step "check to make sure all settings were configured for jetty.ini" do
      ["ssl-host", "ssl-port", "ssl-key", "ssl-cert", "ssl-ca-cert"].each do |k|
        on database, "grep -e '^#{k} = ' #{confd}/jetty.ini"
      end
    end

    step "run puppetdb-ssl-setup again to make sure it is idempotent" do
      on database, "#{sbin_loc}/puppetdb-ssl-setup"
      on database, "diff #{confd}/jetty.ini #{confd}/jetty.ini.bak.ssl_setup_tests"
    end

    step "purposely modify jetty.ini ssl-host and make sure puppetdb-ssl-setup -f fixes it" do
      on database, "sed -i 's/^ssl-host = .*/ssl-host = foobarbaz/' #{confd}/jetty.ini"
      on database, "#{sbin_loc}/puppetdb-ssl-setup -f"
      on database, "grep -e '^ssl-host = foobarbaz' #{confd}/jetty.ini", :acceptable_exit_codes => [1]
    end

    step "purposely modify jetty.ini ssl-host and make sure puppetdb-ssl-setup does not touch it" do
      on database, "sed -i 's/^ssl-host = .*/ssl-host = foobarbaz/' #{confd}/jetty.ini"
      on database, "#{sbin_loc}/puppetdb-ssl-setup"
      on database, "grep -e '^ssl-host = foobarbaz' #{confd}/jetty.ini"
    end
  end

  step "restore original jetty.ini" do
    on database, "cp #{confd}/jetty.ini.bak.ssl_setup_tests #{confd}/jetty.ini"
  end
end
