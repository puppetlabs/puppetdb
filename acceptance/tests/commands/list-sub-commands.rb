test_name "list sub-commands" do
  step "running puppetdb on its own should list all sub-commands" do
    result = on database, "/usr/sbin/puppetdb"
    ["ssl-setup", "import", "export", "anonymize"].each do |k|
      assert_match(/#{k}/, result.stdout, "puppetdb command should list #{k} as a sub-command")
    end
  end
end
