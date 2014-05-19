test_name "duplicate collected resources" do

  if hosts.length < 3
    skip_test "This test requires more at least three hosts"
    next
  end

  exporter1, exporter2, *collectors = hosts

  manifest = <<MANIFEST
node "#{exporter1}" {
  @@notify {"DUPE NOTIFY": }
}
node "#{exporter2}" {
  @@notify {"DUPE NOTIFY": }
}

node #{collectors.map {|collector| "\"#{collector}\""}.join(', ')} {
  Notify <<| title == "DUPE NOTIFY" |>>
}
MANIFEST

  tmpdir = master.tmpdir('puppetdb-storeconfigs')

  manifest_file = File.join(tmpdir, 'site.pp')

  create_remote_file(master, manifest_file, manifest)

  on master, "chmod -R +rX #{tmpdir}"

  with_puppet_running_on master, {
    'master' => {
      'autosign' => 'true',
      'manifest' => manifest_file
    }} do

    step "Run exporters to populate the database" do
      run_agent_on exporter1, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      run_agent_on exporter2, "--test --server #{master}", :acceptable_exit_codes => [0,2]

      # Wait until the catalog has been processed
      sleep_until_queue_empty database
    end

    step "Run agent on collectors and expect failures" do
      collectors.each do |collector|
        result = on collector, "puppet agent --test --server #{master}",
                    :acceptable_exit_codes => [1]
        assert_match("A duplicate resource was found while collecting exported resources, with the type and title Notify[DUPE NOTIFY]",
                     result.output,
                     "#{collector.node_name} collected duplicate resources without failing!")
      end
    end

  end
end
