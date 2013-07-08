step "Run an agent to create the SSL certs" do
  with_puppet_running_on master, {'master' => {'autosign' => 'true'}} do
    run_agent_on database, "--test"
  end
end
