require 'json'

puppetdb_query_url = "http://localhost:8080/pdb/query"
puppetdb_cmd_url = "http://localhost:8080/pdb/cmd"
test_name "structured and trusted facts should be available through facts terminus" do

  structured_data = {"foo"=>[1, 2, 3],
                     "bar"=>{"f" => [3.14, 2.71], "*" => "**","#~" => ""},
                     "baz" => [{"a"=>1},{"b"=>2}]}

  with_puppet_running_on master, {
    'master' => {
      'autosign' => 'true',
      'trusted_node_data' => 'true'
    },
    'main' => {
      'stringify_facts' => 'false'
    }} do

    step "Run agent once to populate database" do
      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => [0,2]
    end

    # Wait until all the commands have been processed
    sleep_until_queue_empty database

    step "Run facts face to find facts for each node" do

      hosts.each do |host|
        result = on master, "puppet facts find #{host.node_name} --terminus puppetdb"
        facts = JSON.parse(result.stdout.strip)
        assert_equal(host.node_name, facts['name'],
                     "Failed to retrieve facts for '#{host.node_name}' via inventory service!")
      end
    end

    step "Query the database for trusted facts" do
      query = CGI.escape('["=","name","trusted"]')
      result = on database, %Q|curl -G '#{puppetdb_query_url}/v4/facts' --data 'query=#{query}'|
      facts = parse_json_with_error(result.stdout)
      assert_equal("remote", facts.first["value"]["authenticated"])
    end

    step "create a custom structured fact" do
      time = Time.now.iso8601
      payload = <<-EOM
      -H "Accept: application/json" -H "Content-Type: application/json" \
      -d '{"command":"replace facts","version":4, \
      "payload":{"environment":"DEV","certname":"#{master}", \
      "timestamp": "#{time}", \
      "producer_timestamp": "#{time}", \
      "values":{"my_structured_fact":#{JSON.generate(structured_data)}}}}' #{puppetdb_cmd_url}/v1
      EOM
      on database, %Q|curl -X POST #{payload}|
    end

    # Wait until all the commands have been processed
    sleep_until_queue_empty database


    step "Ensure that the structured fact is passed through properly" do
      query = CGI.escape('["=","name","my_structured_fact"]')
      result = on database, %Q|curl -G '#{puppetdb_query_url}/v4/facts' --data 'query=#{query}'|
      facts = parse_json_with_error(result.stdout)
      assert_equal(structured_data, facts.first["value"])
    end
  end
end
