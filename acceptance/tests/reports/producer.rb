require 'json'

puppetdb_query_url = "http://localhost:8080/pdb/query"
test_name "validate producer in agent report" do
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

  # Query for all of the reports, but we only care about there being at least one 
  result = on database, %Q|curl -G #{puppetdb_query_url}/v4/reports --data 'query=["=",%20"producer",%20"#{master.node_name}"]'|
  report = JSON.parse(result.stdout)[0]

  assert_not_nil(report, "Should have found at least one report with producer equal to #{master.node_name}")

  producer = report['producer']

  assert_equal(producer, master.node_name, 'Producer should equal the master certname')

end
