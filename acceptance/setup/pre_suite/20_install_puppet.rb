test_config = PuppetDBExtensions.config

def initialize_repo_on_host(host, os)
  case os
  when :debian
    on host, "wget http://apt.puppetlabs.com/puppetlabs-release-$(lsb_release -sc).deb"
    on host, "dpkg -i puppetlabs-release-$(lsb_release -sc).deb"
    on host, "apt-get update"
  when :redhat
    create_remote_file host, '/etc/yum.repos.d/puppetlabs-dependencies.repo', <<-REPO.gsub(' '*8, '')
[puppetlabs-dependencies]
name=Puppet Labs Dependencies - $basearch
baseurl=http://yum.puppetlabs.com/el/$releasever/dependencies/$basearch
gpgkey=http://yum.puppetlabs.com/RPM-GPG-KEY-puppetlabs
enabled=1
gpgcheck=1
    REPO

    create_remote_file host, '/etc/yum.repos.d/puppetlabs-products.repo', <<-REPO.gsub(' '*8, '')
[puppetlabs-products]
name=Puppet Labs Products - $basearch
baseurl=http://yum.puppetlabs.com/el/$releasever/products/$basearch
gpgkey=http://yum.puppetlabs.com/RPM-GPG-KEY-puppetlabs
enabled=1
gpgcheck=1
    REPO

    create_remote_file host, '/etc/yum.repos.d/epel.repo', <<-REPO
[epel]
name=Extra Packages for Enterprise Linux $releasever - $basearch
baseurl=http://download.fedoraproject.org/pub/epel/$releasever/$basearch
mirrorlist=https://mirrors.fedoraproject.org/metalink?repo=epel-$releasever&arch=$basearch
failovermethod=priority
enabled=1
gpgcheck=0
    REPO

  else
    raise ArgumentError, "Unsupported OS '#{os}'"
  end
end


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
  step "Add Puppet Labs repositories" do
    hosts.each do |host|
      initialize_repo_on_host(host, os_families[host.name])
    end
  end

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
  # If our :install_type is :pe then the harness has already installed puppet.
end

