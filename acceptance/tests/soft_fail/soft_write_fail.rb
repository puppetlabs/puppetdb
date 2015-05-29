test_name "soft write failure" do
  databases.each {|db| stop_puppetdb(db)}
  on master, "puppet master --configprint confdir"
  confdir = stdout.chomp
  puppetdb_conf = "#{confdir}/puppetdb.conf"

  step "backup puppetdb.conf and prepare with new setting" do
    on master, "rm -f #{puppetdb_conf}.soft_write_fail"
    on master, "cp -p #{puppetdb_conf} #{puppetdb_conf}.soft_write_fail"
    on master, "echo 'soft_write_failure = true' >> #{puppetdb_conf}"
  end

  teardown do
    # Restore original puppetdb.conf
    on master, "rm -f #{puppetdb_conf}"
    on master, "mv #{puppetdb_conf}.soft_write_fail #{puppetdb_conf}"

    start_puppetdb(database)
  end

  names = hosts.map(&:hostname)

  manifest_file_export = manifest_file_collect = nil

  step "prepare sample content" do
    manifest_export = names.map do |name|
      <<-PIECE
node "#{name}" {
  @@notify { "Hello from #{name}": }
}
      PIECE
    end.join("\n")

    manifest_file_export = create_remote_site_pp(master, manifest_export)

    manifest_collect = names.map do |name|
      <<-PIECE
node "#{name}" {
  @@notify { "Hello from #{name}": }
  Notify <<||>>
}
      PIECE
    end.join("\n")

    manifest_file_collect = create_remote_site_pp(master, manifest_collect)

  end

  step "Run agent with collection and puppetdb stopped making sure it fails" do
    with_puppet_running_on master, {
      'master' => {
        'autosign' => 'true',
      },
      'main' => {
        'environmentpath' => manifest_file_collect,
      }
    } do


      stop_puppetdb(database)

      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => [1]

      start_puppetdb(database)
    end
  end

  step "Run agent with no collection and puppetdb stopped making sure it completes" do
    with_puppet_running_on master, {
      'master' => {
        'autosign' => 'true',
      },
      'main' => {
        'environmentpath' => manifest_file_export,
      }
    } do

      stop_puppetdb(database)

      on hosts, 'puppet master --configprint storeconfigs'

      run_agent_on hosts, "--test --verbose --trace --server #{master}"

      start_puppetdb(database)
    end
  end
end
