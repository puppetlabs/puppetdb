test_config = PuppetDBExtensions.config

def setup_package_repos_on_host(host, os)
  case os
  when :debian
  when :redhat
    create_remote_file host, '/etc/yum.repos.d/CentOS-Base.repo', <<-REPO.gsub(' '*8, '')
[base]
name=CentOS-$releasever - Base
mirrorlist=http://mirrorlist.centos.org/?release=$releasever&arch=$basearch&repo=os
baseurl=http://mirror.centos.org/centos/$releasever/os/$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-$releasever

[contrib]
name=CentOS-$releasever - Contrib
mirrorlist=http://mirrorlist.centos.org/?release=$releasever&arch=$basearch&repo=contrib
baseurl=http://mirror.centos.org/centos/$releasever/contrib/$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-$releasever

[centosplus]
name=CentOS-$releasever - Plus
mirrorlist=http://mirrorlist.centos.org/?release=$releasever&arch=$basearch&repo=centosplus
baseurl=http://mirror.centos.org/centos/$releasever/centosplus/$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-$releasever

[extras]
name=CentOS-$releasever - Extras
mirrorlist=http://mirrorlist.centos.org/?release=$releasever&arch=$basearch&repo=extras
baseurl=http://mirror.centos.org/centos/$releasever/extras/$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-$releasever

[updates]
name=CentOS-$releasever - Updates
mirrorlist=http://mirrorlist.centos.org/?release=$releasever&arch=$basearch&repo=updates
baseurl=http://mirror.centos.org/centos/$releasever/updates/$basearch/
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-$releasever
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

step "Setup package repos" do
  os_families = test_config[:os_families]
  hosts.each do |host|
    setup_package_repos_on_host(host, os_families[host.name])
  end
end

