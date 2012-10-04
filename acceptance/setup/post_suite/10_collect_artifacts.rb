
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
  PuppetAcceptance::Log.notify `scp root@#{database}:/var/log/puppetdb/puppetdb.log ./artifacts 2>&1`
end
