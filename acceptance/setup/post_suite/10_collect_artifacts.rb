
step "Create artifacts directory" do
  unless File.directory?('artifacts')
    Dir.mkdir("artifacts")
  end
end
step "Collect puppetdb log file" do

  scp_from(database, "/var/log/puppetdb/puppetdb.log", "./artifacts")

end
