test_config = PuppetDBExtensions.config

step "Create file on PuppetDB server to disable version checking" do
  on(database, "mkdir -p /var/lib/puppetdb && touch /var/lib/puppetdb/DISABLE_VERSION_CHECK")
end
