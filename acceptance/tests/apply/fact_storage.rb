require 'json'

test_name "facts should be available through facts terminus when using apply" do
  create_remote_file(master, '/tmp/routes-apply.yaml', <<-EOS)
apply:
  facts:
    terminus: facter
    cache: puppetdb_apply
  EOS

  step "Run apply on host to populate database" do
    result = on(master, "FACTER_foo='testfoo' puppet apply --route_file /tmp/routes-apply.yaml -e 'notice($foo)'")
    assert_match(/testfoo/, result.output)
  end

  step "Run again to ensure we aren't using puppetdb for the answer" do
    result = on(master, "FACTER_foo='second test' puppet apply --route_file /tmp/routes-apply.yaml -e 'notice($foo)'")
    assert_match(/second test/, result.output)
  end

  # Wait until all the commands have been processed
  sleep_until_queue_empty database

  step "Run facts face to find facts for each node" do
    result = on master, "puppet facts find #{master.node_name} --terminus puppetdb"
    facts = JSON.parse(result.stdout.strip)
    assert_equal('second test', facts['values']['foo'], "Failed to retrieve facts for '#{master.node_name}' via inventory service!")
  end
end
