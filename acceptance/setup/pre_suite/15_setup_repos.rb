def initialize_repo_on_host(host, os, nightly)
  apt_base = 'http://apt.puppetlabs.com'
  yum_base = 'http://yum.puppetlabs.com'
  nightlies_base = 'http://nightlies.puppet.com'
  case os
  when :debian

    # For openjdk8
    if host['platform'].version == '8'
      create_remote_file(host,
                         "/etc/apt/sources.list.d/jessie-backports.list",
                         "deb http://httpredir.debian.org/debian jessie-backports main")
    end

    if options[:type] == 'aio' then
      if nightly
        ## PC1 repos
        on host, "curl -O #{apt_base}/puppetlabs-release-pc1-$(lsb_release -sc).deb"
        on host, "dpkg -i puppetlabs-release-pc1-$(lsb_release -sc).deb"

        ## Nightly repos
        on host, "curl -O #{nightlies_base}/apt/puppet-nightly-release-$(lsb_release -sc).deb"
        on host, "dpkg -i puppet-nightly-release-$(lsb_release -sc).deb"
      else
        on host, "curl -O #{apt_base}.puppetlabs.com/puppetlabs-release-pc1-$(lsb_release -sc).deb"
        on host, "dpkg -i puppetlabs-release-pc1-$(lsb_release -sc).deb"
      end
    else
      on host, "curl -O #{apt_base}.puppetlabs.com/puppetlabs-release-$(lsb_release -sc).deb"
      on host, "dpkg -i puppetlabs-release-$(lsb_release -sc).deb"
    end
    on host, "apt-get update"
    on host, "apt-get install debian-archive-keyring"
  when :redhat
    if options[:type] == 'aio' then
      /^(el|centos)-(\d+)-(.+)$/.match(host.platform)
      variant = ($1 == 'centos') ? 'el' : $1
      version = $2
      arch = $3

      if nightly
        ## PC1 repos
        on host, "curl -O #{yum_base}/puppetlabs-release-pc1-#{variant}-#{version}.noarch.rpm"
        on host, "rpm -i puppetlabs-release-pc1-#{variant}-#{version}.noarch.rpm"

        ## Nightly repos
        on host, "curl -O #{nightlies_base}/yum/puppet-nightly-release-#{variant}-#{version}.noarch.rpm"
        on host, "rpm -i puppet-nightly-release-#{variant}-#{version}.noarch.rpm"
      else
        on host, "curl -O #{yum_base}/puppetlabs-release-pc1-#{variant}-#{version}.noarch.rpm"
        on host, "rpm -i puppetlabs-release-pc1-#{variant}-#{version}.noarch.rpm"
      end
    else
      on host, "yum clean all -y"
      on host, "yum upgrade -y"
      create_remote_file host, '/etc/yum.repos.d/puppetlabs-dependencies.repo', <<-REPO.gsub(' '*8, '')
[puppetlabs-dependencies]
name=Puppet Labs Dependencies - $basearch
baseurl=#{yum_base}/el/$releasever/dependencies/$basearch
gpgkey=#{yum_base}/RPM-GPG-KEY-puppetlabs
enabled=1
gpgcheck=1
      REPO

      create_remote_file host, '/etc/yum.repos.d/puppetlabs-products.repo', <<-REPO.gsub(' '*8, '')
[puppetlabs-products]
name=Puppet Labs Products - $basearch
baseurl=#{yum_base}.com/el/$releasever/products/$basearch
gpgkey=#{yum_base}/RPM-GPG-KEY-puppetlabs
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
    end
  when :fedora
    create_remote_file host, '/etc/yum.repos.d/puppetlabs-dependencies.repo', <<-REPO.gsub(' '*8, '')
[puppetlabs-dependencies]
name=Puppet Labs Dependencies - $basearch
baseurl=#{yum_base}/fedora/f$releasever/dependencies/$basearch
gpgkey=#{yum_base}/RPM-GPG-KEY-puppetlabs
enabled=1
gpgcheck=1
    REPO

    create_remote_file host, '/etc/yum.repos.d/puppetlabs-products.repo', <<-REPO.gsub(' '*8, '')
[puppetlabs-products]
name=Puppet Labs Products - $basearch
baseurl=#{yum_base}/fedora/f$releasever/products/$basearch
gpgkey=#{yum_base}/RPM-GPG-KEY-puppetlabs
enabled=1
gpgcheck=1
    REPO
  else
    raise ArgumentError, "Unsupported OS '#{os}'"
  end
end

unless (test_config[:skip_presuite_provisioning])
  step "Install Puppet Labs repositories" do
    hosts.each do |host|
      initialize_repo_on_host(host, test_config[:os_families][host.name], test_config[:nightly])
    end
  end
end
