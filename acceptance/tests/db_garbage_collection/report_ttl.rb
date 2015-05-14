require 'json'
  confd = "#{puppetdb_confdir(database)}/conf.d"

puppetdb_query_url = "http://localhost:8080/pdb/query"
test_name "validate that reports are deleted based on report-ttl setting" do
  step "setup a test manifest for the master and perform agent runs" do
    manifest = <<-MANIFEST
      node default {
        @@notify { "exported_resource": }
        notify { "non_exported_resource": }
     }
    MANIFEST

    run_agents_with_new_site_pp(master, manifest)
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  step "Verify that we have reports for every agent" do
    agents.each do |agent|
      # Query for all of the reports for this node:
      result = on database, %Q|curl -G #{puppetdb_query_url}/v4/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|
      reports = JSON.parse(result.stdout)
      assert(reports.length > 0, "Expected at least one report for node '#{agent.node_name}'")
    end
  end

  step "Sleep for one second to make sure we have a ttl to exceed" do
    sleep 1
  end


  step "Back up the database.ini file and create a temp one with a ttl" do
    on database, "cp #{confd}/database.ini #{confd}/database.ini.bak"
    modify_config_setting(database, "database.ini", "database",
                          "report-ttl", "1s")
  end


  step "Restart PuppetDB to pick up config changes" do
    restart_puppetdb database
  end

  # TODO: this is really fragile, it would be better if there were some way
  # to tell that the GC had finished.  We could maybe scrape the logs if this
  # proves too fragile.
  step "sleep 5 seconds to allow GC to complete" do
    sleep 5
  end

  step "Verify that the reports for every agent have been deleted" do
    agents.each do |agent|
      # Query for all of the reports for this node:
      result = on database, %Q|curl -G #{puppetdb_query_url}/v4/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|
      reports = JSON.parse(result.stdout)
      assert(0, "Expected zero reports for node '#{agent.node_name}'")
    end
  end

  step "Restore the original database.ini file and restart puppetdb" do
    if database.is_pe?
      on database, "mv #{confd}/database.ini.bak #{confd}/database.ini ; chown pe-puppetdb:pe-puppetdb #{confd}/database.ini"
    else
      on database, "mv #{confd}/database.ini.bak #{confd}/database.ini ; chown puppetdb:puppetdb #{confd}/database.ini"
    end
    restart_puppetdb database
  end

end

