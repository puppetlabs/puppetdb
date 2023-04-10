os_families = {}

step "Determine host OS's" do
  os_families = hosts.inject({}) do |result, host|
    result[host.name] = get_os_family(host)
    result
  end
end

PuppetDBExtensions.initialize_test_config(options,
            os_families)
