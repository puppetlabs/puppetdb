test_name "list sub-commands" do
  bin_loc = puppetdb_bin_dir(database)

  step "running puppetdb on its own should list all sub-commands" do
    result = on database, "#{bin_loc}/puppetdb"
    ["delete-reports", "ssl-setup", "foreground"].each do |k|
      assert_match(/#{k}/, result.stdout, "puppetdb command should list #{k} as a sub-command")
    end
  end
end
