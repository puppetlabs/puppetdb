test_name "puppetdb ssl-setup on nonproduction environment" do
  confdir = on(database, puppet_master("--configprint confdir")).stdout.chomp
  bin_loc = "#{puppetdb_bin_dir(database)}"
  step "back up existing puppet.conf" do
    on database, "cp #{confdir}/puppet.conf #{confdir}/puppet.conf.bak"
  end
  step "ensure proper exit code on ssl-setup" do
    on database, "#{bin_loc}/puppet config set environment foo --section main"
    result = on database, "#{bin_loc}/puppetdb ssl-setup", :acceptable_exit_codes => [0]
  end
  step "restore original puppet.conf" do
    on database, "mv #{confdir}/puppet.conf.bak #{confdir}/puppet.conf"
  end
end
