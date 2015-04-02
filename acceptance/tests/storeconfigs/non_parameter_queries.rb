test_name "non-parameter queries" do

  if hosts.length <= 1
    skip_test "This test requires more than one host"
    next
  end

  exporter, *collectors = hosts

  dir = collectors.first.tmpdir('collections')

  manifest = <<MANIFEST
node "#{exporter}" {
  @@file { 'file-a':
    path => "#{dir}/file-a",
    ensure => present,
    tag => here,
  }

  @@file { 'file-b':
    path => "#{dir}/file-b",
    ensure => present,
    tag => [here, there],
  }

  @@file { 'file-c':
    path => "#{dir}/file-c",
    ensure => present,
    tag => [there],
  }
}

node #{collectors.map {|collector| "\"#{collector}\""}.join(', ')} {
  # The magic of facts!
  include $test_name

  file { "#{dir}":
    ensure => directory,
  }
}

class title_query {
  File <<| title == 'file-c' |>>
}

class title_query_no_match {
  File <<| title == 'file' |>>
}

class title_query_bad_characters {
  File <<| title != 'a string with spaces and & and ? in it' |>>
}

class tag_query {
  File <<| tag == 'here' |>>
}

class tag_inverse_query {
  File <<| tag != 'here' |>>
}

class tag_uppercase_query {
  File <<| tag == 'HERE' |>>
}
MANIFEST

  manifest_path = create_remote_site_pp(master, manifest)
  with_puppet_running_on master, {
    'master' => {
      'autosign' => 'true',
    },
    'main' => {
      'environmentpath' => manifest_path,
    }} do

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

    step "title queries should work" do
      test_collection.call collectors, "title_query", %w[file-c]
    end

    step "title queries should work when nothing matches" do
      test_collection.call collectors, "title_query_no_match", %w[]
    end

    step "title queries should work even if they contain URI-invalid characters" do
      test_collection.call collectors, "title_query_bad_characters", %w[file-a file-b file-c]
    end

    step "tag queries should work" do
      test_collection.call collectors, "tag_query", %w[file-a file-b]
    end

    step "'not' tag queries should work" do
      test_collection.call collectors, "tag_inverse_query", %w[file-c]
    end

    step "tag queries should be case-insensitive" do
      test_collection.call collectors, "tag_uppercase_query", %w[file-a file-b]
    end
  end
end
