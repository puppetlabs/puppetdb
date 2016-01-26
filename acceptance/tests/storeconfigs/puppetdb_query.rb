test_name "puppetdb_query should work" do
  step "clear puppetdb database" do
    clear_and_restart_puppetdb(database)
  end

  names = hosts.map(&:hostname)

  manifest = <<-CONTENT
node default {
  $counts = puppetdb_query(["from", "catalogs",
                            ["extract", [["function", "count"]]]])
  $count = $counts[0]['count']
  file { '/tmp/test_puppetdb_query.txt':
    ensure  => present,
    content => "${count}"
  }
}
    CONTENT


  manifest_path = create_remote_site_pp(master, manifest)
  with_puppet_running_on master, {
    'master' => {
      'autosign' => 'true',
    },
    'main' => {
      'environmentpath' => manifest_path
    }
  } do

    step "Run agent once to populate database" do
      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => [0,2]
    end
    # Wait until all the commands have been processed
    sleep_until_queue_empty database

    step "Run agent second time to populate test files" do
      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => [0,2]
    end

    step "Check that the puppetdb_query() call was a success" do
      on hosts[0], "cat /tmp/test_puppetdb_query.txt"
      created = stdout.split.map(&:strip).sort[0]
      assert_equal("#{hosts.length}", "#{created}", "expected '#{hosts.length}' nodes but got '#{created}'")
    end
  end
end
