require 'json'

test_name "validate that reports are deleted based on report-ttl setting" do

  with_master_running_on master, "--reports=store,puppetdb --autosign true", :preserve_ssl => true do
    step "Run agents once to generate reports" do
      run_agent_on agents, "--test --server #{master}"
    end
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  step "Verify that we have reports for every agent" do
      agents.each do |agent|
        # Query for all of the reports for this node:
        result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|
        reports = JSON.parse(result.stdout)
        assert(reports.length > 0, "Expected at least one report for node '#{agent.node_name}'")
      end
  end

  step "Sleep for one second to make sure we have a ttl to exceed" do
    sleep 1
  end


  step "Back up the database.ini file and create a temp one with a ttl" do
    on database, "cp /etc/puppetdb/conf.d/database.ini /etc/puppetdb/conf.d/database.ini.bak"
    # TODO: this could/should be done via the module once we support it
    on database, "echo 'report-ttl = 1s' >> /etc/puppetdb/conf.d/database.ini"
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
      result = on database, %Q|curl -G -H 'Accept: application/json' http://localhost:8080/experimental/reports --data 'query=["=",%20"certname",%20"#{agent.node_name}"]'|
      reports = JSON.parse(result.stdout)
      assert(0, "Expected zero reports for node '#{agent.node_name}'")
    end
  end

  step "Restore the original database.ini file and restart puppetdb" do
    on database, "mv /etc/puppetdb/conf.d/database.ini.bak /etc/puppetdb/conf.d/database.ini ; chown puppetdb:puppetdb /etc/puppetdb/conf.d/database.ini"
    restart_puppetdb database
  end

end

