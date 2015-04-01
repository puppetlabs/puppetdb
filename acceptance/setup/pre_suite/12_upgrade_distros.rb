def upgrade_pkgs_on_host(host, os)
  case os
  when :debian
    on host, "apt-get update"
    on host, "DEBIAN_FRONTEND=noninteractive apt-get -o Dpkg::Options::=\"--force-confnew\" --force-yes -fuy dist-upgrade"
  when :redhat
    on host, "yum clean all -y"
    on host, "yum upgrade -y"
  when :fedora
    on host, "yum clean all -y"
    on host, "yum upgrade -y"
  else
    raise ArgumentError, "Unsupported OS '#{os}'"
  end
end

unless (test_config[:skip_presuite_provisioning])
  step "Upgrade each host" do
    hosts.each do |host|
      upgrade_pkgs_on_host(host, test_config[:os_families][host.name])
    end
  end
end
