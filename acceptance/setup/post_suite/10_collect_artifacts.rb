
step "Create artifacts directory" do
  unless File.directory?('artifacts')
    Dir.mkdir("artifacts")
  end
end
step "Collect puppetdb log file" do
  # Would like to do this through the harness, but
  # there is currently only an "scp_to" method on
  # that goes *out* to the hosts, no method that
  # scp's *from* the hosts.

  ssh_host = database['ip'] || database.name
  scp_cmd = "scp root@#{ssh_host}:/var/log/puppetdb/puppetdb.log ./artifacts 2>&1"
  PuppetAcceptance::Log.notify("Executing scp command:\n\n#{scp_cmd}\n\n")
  result = `#{scp_cmd}`
  status = $?
  PuppetAcceptance::Log.notify(result)
  assert(status.success?, "Collecting puppetdb log file failed with exit code #{status.exitstatus}.")
end
