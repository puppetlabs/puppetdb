test_name "queries against deactivated nodes" do

  if hosts.length <= 1
    skip_test "This test requires more than one host"
    next
  end

  exporter, *collectors = hosts

  dir = collectors.first.tmpdir('collections')

  manifest = <<MANIFEST
node "#{exporter}" {
  @@file { "#{dir}/from_exporter":
    ensure => present,
    mode => "0777",
  }
}

node #{collectors.map {|collector| "\"#{collector.node_name}\""}.join(', ')} {
  # The magic of facts!
  include $test_name

  file { "#{dir}":
    ensure => directory,
  }
}

# Technically these classes are all the same, but they represent several
# distinct test cases.
class not_deactivated {
  File <<| |>>
}

class deactivated {
  File <<| |>>
}

class reactivated {
  File <<| |>>
}
MANIFEST

  manifest_path = create_remote_site_pp(master, manifest)

  with_puppet_running_on master, {
    'master' => {
      'autosign' => 'true',
    },
    'main' => {
      'environmentpath' => manifest_path,
    }
  } do

    step "Run exporter to populate the database" do
      run_agent_on exporter, "--test --server #{master}", :acceptable_exit_codes => [0,2]

      # Wait until the catalog has been processed
      sleep_until_queue_empty database
    end

    test_collection = proc do |nodes, test_name, expected|
      on nodes, "rm -rf #{dir}"

      on nodes, "FACTER_test_name=#{test_name} puppet agent --test --server #{master}", :acceptable_exit_codes => [0,2]

      nodes.each do |node|
        on node, "ls #{dir}"
        created = stdout.split.map(&:strip).sort
        assert_equal(expected, created, "#{node} collected #{created.join(', ')} instead of #{expected.join(', ')} for #{test_name}")
      end
    end

    step "ensure the queue is drained" do
      sleep_until_queue_empty database
    end

    step "resources should be collected before deactivation" do
      test_collection.call collectors, "not_deactivated", %w[from_exporter]
    end

    step "deactivate the exporter node" do
      # Deactivate the node and wait until it's been processed
      # FYI `puppet node deactivate` and `puppet node status` rely on the the
      # pdb terminus, so they can only be run on the master
      on master, "puppet node deactivate '#{exporter.node_name}'"
      sleep_until_queue_empty database

      # Check that it's actually deactivated
      on master, "puppet node status '#{exporter.node_name}'" do
        assert_match(/Deactivated at/, result.output, "#{exporter.node_name} was not properly deactivated")
      end
    end

    step "deactivated nodes should be ignored" do
      test_collection.call collectors, "deactivated", %w[]
    end

    step "Run exporter against to reactivate it" do
      run_agent_on exporter, "--test --server #{master}", :acceptable_exit_codes => [0,2]

      # Wait until the catalog has been processed
      sleep_until_queue_empty database

      on master, "puppet node status '#{exporter.node_name}'" do
        assert_match(/Currently active/, result.output, "#{exporter.node_name} was not properly reactivated")
      end
    end

    step "resources should be collected against after reactivation" do
      test_collection.call collectors, "reactivated", %w[from_exporter]
    end
  end
end
