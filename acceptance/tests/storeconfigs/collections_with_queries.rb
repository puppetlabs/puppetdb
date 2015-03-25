test_name "collections with queries" do

  if hosts.length <= 1
    skip_test "This test requires more than one host"
    next
  end

  exporter, *collectors = hosts

  dir = collectors.first.tmpdir('collections')

  manifest = <<MANIFEST
node "#{exporter}" {
  @@file { "#{dir}/file-a":
    ensure  => present,
    mode    => "0777",
    content => "foo"
  }

  @@file { "#{dir}/file-b":
    ensure  => present,
    mode    => "0755",
    content => "bar"
  }

  @@file { "#{dir}/file-c":
    ensure  => present,
    mode    => "0744",
    content => "foo",
  }

  @@file { "#{dir}/file-d":
    ensure  => present,
    mode    => "0744",
    content => "bar"
  }
}

node #{collectors.map {|collector| "\"#{collector}\""}.join(', ')} {
  # The magic of facts!
  include $test_name

  file { "#{dir}":
    ensure => directory,
  }
}

class equal_query {
  File <<| mode == "0744" |>>
}

class not_equal_query {
  File <<| mode != "0755" |>>
}

class or_query {
  File <<| mode == "0755" or content == "bar" |>>
}

class and_query {
  File <<| mode == "0744" and content == "foo" |>>
}

class nested_query {
  File <<| (mode == "0777" or mode == "0755") and content == "bar" |>>
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

    step "= queries should work" do
      test_collection.call collectors, "equal_query", %w[file-c file-d]
    end

    step "!= queries should work" do
      test_collection.call collectors, "not_equal_query", %w[file-a file-c file-d]
    end

    step "queries joined with 'or' should work" do
      test_collection.call collectors, "or_query", %w[file-b file-d]
    end

    step "queries joined with 'and' should work" do
      test_collection.call collectors, "and_query", %w[file-c]
    end

    step "complex nested queries should work" do
      test_collection.call collectors, "nested_query", %w[file-b]
    end
  end
end
