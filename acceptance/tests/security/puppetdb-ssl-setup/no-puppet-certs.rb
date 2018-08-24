test_name "puppetdb ssl-setup with no puppet certs" do
  db_conf_dir = puppetdb_confdir(database)
  db_ssl_dir = "#{db_conf_dir}/ssl"
  db_confd = "#{puppetdb_confdir(database)}/conf.d"
  bin_loc = "#{puppetdb_bin_dir(database)}"

  ssl_dir = on(database, "puppet config print ssldir --section master").stdout.chomp

  step "backup jetty.ini and puppetdb ssl certs" do
    on database, "cp #{db_confd}/jetty.ini #{db_confd}/jetty.ini.bak.ssl_setup_tests"
    on database, "rm -rf #{db_ssl_dir}.bak.ssl_setup_tests"
    on database, "if [ -e #{db_ssl_dir} ]; then mv #{db_ssl_dir} #{db_ssl_dir}.bak.ssl_setup_tests; fi"
  end

  teardown do
    on database, "cp #{db_confd}/jetty.ini.bak.ssl_setup_tests #{db_confd}/jetty.ini"
    # This restores the certs if they weren't restored already
    on database, "if [ ! -e #{ssl_dir} -a -e #{ssl_dir}.bak.ssl_setup_tests ]; then mv #{ssl_dir}.bak.ssl_setup_tests #{ssl_dir}; fi"
    on database, "if [ ! -e #{db_ssl_dir} -a -e #{db_ssl_dir}.bak.ssl_setup_tests ]; then mv #{db_ssl_dir}.bak.ssl_setup_tests #{db_ssl_dir}; fi"
  end

  # The goal of this test is, make sure the user receives a nice error when
  # the agent certs are missing, as this implies Puppet has not ran yet.
  step "run puppetdb ssl-setup with no puppet certs, and make sure it returns a meaningful error" do
    on database, "rm -rf #{ssl_dir}.bak.ssl_setup_tests"
    on database, "mv #{ssl_dir} #{ssl_dir}.bak.ssl_setup_tests"
    result = on database, "#{bin_loc}/puppetdb ssl-setup", :acceptable_exit_codes => [1]
    assert_match(/Warning: Unable to find all puppet certificates to copy/, result.output)
  end

  # Now we restore the certificates
  step "restore certificates" do
    on database, "rm -rf #{ssl_dir}"
    on database, "mv #{ssl_dir}.bak.ssl_setup_tests #{ssl_dir}"
  end

  step "retest puppetdb ssl-setup again now there are certs" do
    on database, "#{bin_loc}/puppetdb ssl-setup"
  end
end
