os_families = {}
module_path = nil

step "Determine host OS's" do
  os_families = hosts.inject({}) do |result, host|
    result[host.name] = get_os_family(host)
    result
  end
end

step "Determine module path on database node" do
  module_path = database.tmpfile("puppetdb_modulepath")
end

PuppetDBExtensions.initialize_test_config(options,
            os_families, module_path)

