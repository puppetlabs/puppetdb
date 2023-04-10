#!/usr/bin/env ruby

test_config = PuppetDBExtensions.config

def uninstall_package(host, os_families, pkg_name)
  os = os_families[host.name]
  case os
  when :debian
    on(host, "apt-get -f -y purge #{pkg_name} ")
  when :redhat
    on(host, "yum -y remove #{pkg_name}")
  else
    raise ArgumentError, "Unsupported OS family: '#{os}'"
  end
end


step "Stop puppetdb" do
  stop_puppetdb(database)
end

if (test_config[:purge_after_run])
  if (test_config[:install_type] == :package)
    step "Uninstall packages" do
      uninstall_package(database, test_config[:os_families], "puppetdb")
      uninstall_package(master, test_config[:os_families], "puppetdb-termini")
      hosts.each do |host|
        uninstall_package(host, test_config[:os_families], "puppet")
      end
    end
  end
end
