test_name "argument handling" do
  bin_loc = puppetdb_bin_dir(database)

  step "running 'puppetdb ssl-setup -h' should not error out" do
    on database, "#{bin_loc}/puppetdb ssl-setup -h"
  end
end
