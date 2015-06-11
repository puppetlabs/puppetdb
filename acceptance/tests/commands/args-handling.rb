test_name "argument handling" do
  bin_loc = puppetdb_bin_dir(database)

  ["ssl-setup", "import", "export", "anonymize"].each do |k|
    step "running puppetdb #{k} -h should not error out" do
      on database, "#{bin_loc}/puppetdb #{k} -h"
    end

    # We don't test ssl-setup here, because its actions without any arguments
    # are tested elsewhere.
    next if k == 'ssl-setup'
    step "running puppetdb-#{k} with no arguments should not error out" do
      on database, "#{bin_loc}/puppetdb #{k}", :acceptable_exit_codes => [1]
      assert_match(/Missing required argument/, stdout)
    end
  end
end
