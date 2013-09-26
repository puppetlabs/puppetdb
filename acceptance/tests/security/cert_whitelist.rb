test_name "certificate whitelisting" do

  confd = "#{puppetdb_confdir(database)}/conf.d"
  dbname = database.node_name

  on database, puppet_agent("--configprint ssldir")
  ssldir = stdout.chomp

  step "reconfigure PuppetDB to use certificate whitelist" do
    on database, "cp #{confd}/jetty.ini #{confd}/jetty.ini.bak"
    on database, "grep -q ^certificate-whitelist #{confd}/jetty.ini || echo 'certificate-whitelist = #{confd}/whitelist' >> #{confd}/jetty.ini"
  end

  # Execute a curl from the database to itself using HTTPS, using the
  # supplied whitelist, and verify the response is what we expect
  curl_against_whitelist = proc do |whitelist, expected_status_code|
    create_remote_file database, "#{confd}/whitelist", whitelist
    on database, "chmod 644 #{confd}/whitelist"
    restart_puppetdb database
    on database, "curl -sL -w '%{http_code}\\n' " +
                 "--cacert #{ssldir}/certs/ca.pem " +
                 "--cert #{ssldir}/certs/#{dbname}.pem " +
                 "--key #{ssldir}/private_keys/#{dbname}.pem "+
                 "https://#{dbname}:8081/v3/metrics/mbeans " +
                 "-o /dev/null"
    actual_status_code = stdout.chomp
    assert_equal expected_status_code.to_s, actual_status_code, "Reqs from #{dbname} with whitelist '#{whitelist}' should return #{expected_status_code}, not #{actual_status_code}"
  end

  step "hosts in whitelist should be allowed in" do
    curl_against_whitelist.call "#{dbname}\n", 200
  end

  step "hosts not in whitelist should be forbidden" do
    curl_against_whitelist.call "", 403
  end

  step "restore original jetty.ini" do
    on database, "mv #{confd}/jetty.ini.bak #{confd}/jetty.ini"
    on database, "chmod 644 #{confd}/jetty.ini"
    on database, "rm #{confd}/whitelist"
    restart_puppetdb database
  end
end
