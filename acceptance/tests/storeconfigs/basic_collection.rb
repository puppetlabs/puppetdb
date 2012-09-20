test_name "general collection should get all exported resources except the host's" do

  names = hosts.map(&:name)

  manifest = names.map do |name|
    <<-PIECE
node "#{name}" {
  @@notify { "Hello from #{name}": }
  notify { "#{name} only": }
}
    PIECE
  end.join("\n")

  manifest << "\nNotify <<| |>>"

  tmpdir = master.tmpdir('storeconfigs')

  manifest_file = File.join(tmpdir, 'site.pp')

  create_remote_file(master, manifest_file, manifest)

  on master, "chmod -R +rX #{tmpdir}"

  with_master_running_on master, "--autosign true --manifest #{manifest_file}", :preserve_ssl => true do

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
end
