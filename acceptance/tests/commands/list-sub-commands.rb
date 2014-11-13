test_name "list sub-commands" do
  step "running puppetdb on its own should list all sub-commands" do
    result = on database, "/usr/bin/puppetdb"
    ["ssl-setup", "import", "export", "anonymize", "foreground"].each do |k|
      assert_match(/#{k}/, result.stdout, "puppetdb command should list #{k} as a sub-command")
    end
  end
end
