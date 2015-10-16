test_name "general collection should get all exported resources except the host's" do
  step "clear puppetdb database" do
    clear_and_restart_puppetdb(database)
  end

  names = hosts.map(&:hostname)

  manifest = names.map do |name|
    <<-PIECE
node "#{name}" {
  @@notify { "Hello from #{name}": }
  notify { "#{name} only": }
}
    PIECE
  end.join("\n")

  manifest << "\nNotify <<| |>>"

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

    step "Run agent to collect resources" do

      run_agent_on hosts, "--test --server #{master}", :acceptable_exit_codes => [0,2] do
        hostname = result.host

        names.each do |name|
          assert_match(/Hello from #{CGI.escape(name)}/, result.output, "#{hostname} failed to collect from #{name}")
        end
      end
    end
  end

  step "attempt export with undef array elements" do
    manifest = <<-MANIFEST
    @@notify { "test":
            tag => [undef, "a", "b"],
            }
    MANIFEST

    manifest_path = create_remote_site_pp(master, manifest)
    with_puppet_running_on master, {
      'master' => {
        'autosign' => 'true',
    },
    'main' => {
      'environmentpath' => manifest_path
    }
    } do

      step "run agent on master to ensure no failure" do
        run_agent_on master, "--test --server #{master}", :acceptable_exit_codes => [0,2]
      end
    end
  end
end
