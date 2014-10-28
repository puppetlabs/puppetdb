step "Run an agent to create the SSL certs" do
  databases.each do |database|
    with_puppet_running_on(master, {'master' => {'autosign' => 'true', 'trace' => 'true'}}) do
      run_agent_on(database, "--test")
    end
  end
end
