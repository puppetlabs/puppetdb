test_config = PuppetDBExtensions.config

def install_puppet_on_host(host, os)
  case os
  when :debian
    on host, "apt-get install -y puppet"
  when :redhat
    on host, "yum install -y puppet"
  else
    raise ArgumentError, "Unsupported OS '#{os}'"
  end
end

def install_puppet_from_package(os_families)
  step "Install puppet" do
    hosts.each do |host|
      install_puppet_on_host(host, os_families[host.name])
    end
  end
end

step "Install Puppet" do
  case test_config[:install_type]
  when :package
    install_puppet_from_package(test_config[:os_families])
  end
  # If our :install_type is :git or :pe, then the harness has already installed
  # puppet.
end

