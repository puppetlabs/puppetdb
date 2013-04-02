test_name "puppetdb-ssl-setup" do
  confd = "#{puppetdb_confdir(database)}/conf.d"

  step "backup jetty.ini and setup template" do
    on database, "cp #{confd}/jetty.ini #{confd}/jetty.ini.bak.ssl_setup_tests"
  end

  step "check to make sure all settings were configured for jetty.ini" do
    ["ssl-host", "ssl-port", "key-password", "trust-password", "keystore", "truststore"].each do |k|
      on database, "grep -e '^#{k} = ' #{confd}/jetty.ini"
    end
  end

  step "run puppetdb-ssl-setup again to make sure it is idempotent" do
    on database, "puppetdb-ssl-setup"
    on database, "diff #{confd}/jetty.ini #{confd}/jetty.ini.bak.ssl_setup_tests"
  end

  step "purposely modify jetty.ini ssl-host and make sure puppetdb-ssl-setup does not touch it" do
    on database, "sed -i 's/^ssl-host = .*/ssl-host = foobarbaz/' #{confd}/jetty.ini"
    on database, "puppetdb-ssl-setup"
    on database, "grep -e '^ssl-host = foobarbaz' #{confd}/jetty.ini"
  end

  step "purposely modify jetty.ini ssl-host and make sure puppetdb-ssl-setup -f fixes it" do
    on database, "sed -i 's/^ssl-host = .*/ssl-host = foobarbaz/' #{confd}/jetty.ini"
    on database, "puppetdb-ssl-setup -f"
    on database, "grep -e '^ssl-host = foobarbaz' #{confd}/jetty.ini", :acceptable_exit_codes => [1]
  end

  step "restore original jetty.ini" do
    on database, "cp #{confd}/jetty.ini.bak.ssl_setup_tests #{confd}/jetty.ini"
  end
end
