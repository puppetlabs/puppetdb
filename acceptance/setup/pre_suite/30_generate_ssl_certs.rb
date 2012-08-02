step "Run an agent to create the SSL certs" do
  with_master_running_on master, "--autosign true" do
    run_agent_on database, "--test --server #{master}"
  end
end
