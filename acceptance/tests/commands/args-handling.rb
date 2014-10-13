test_name "argument handling" do
  ["ssl-setup", "import", "export", "anonymize"].each do |k|
    step "running puppetdb #{k} -h should not error out" do
      on database, "/usr/bin/puppetdb #{k} -h"
    end

    # We don't test ssl-setup here, because its actions without any arguments
    # are tested elsewhere.
    next if k == 'ssl-setup'
    step "running puppetdb-#{k} with no arguments should not error out" do
      on database, "/usr/bin/puppetdb #{k}", :acceptable_exit_codes => [1]
      assert_match(/Missing required argument/, stdout)
    end
  end
end
