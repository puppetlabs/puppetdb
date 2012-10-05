test_config = PuppetDBExtensions.config
os = test_config[:os_families][database.name]


if (test_config[:install_type] == :package)

  step "Add development repository on PuppetDB server" do
    case os
    when :debian
      # TODO: account for branches besides master!
      apt_url = "http://neptune.puppetlabs.lan/dev/puppetdb/master"
      if (test_config[:use_s3_repos])
        # TODO: account for branches
        apt_url = "http://puppetdb-apt-prerelease.s3.amazonaws.com/debian"
      end

      # TODO: this could be (maybe?) ported over to use the puppetlabs-apt module.
      on database, "echo deb #{apt_url} $(lsb_release -sc) main >> /etc/apt/sources.list"
      on database, "curl #{apt_url}/pubkey.gpg |apt-key add -"
      on database, "apt-get update"
    when :redhat
      # TODO: account for branches besides master!
      yum_url = "http://neptune.puppetlabs.lan/dev/puppetdb/master"
      if (test_config[:use_s3_repos])
        # TODO: account for branches
        yum_url = "http://puppetdb-yum-prerelease.s3.amazonaws.com"
      end

      create_remote_file database, '/etc/yum.repos.d/puppetlabs-prerelease.repo', <<-REPO
[puppetlabs-development]
name=Puppet Labs Development - $basearch
baseurl=#{yum_url}/el/$releasever/products/$basearch
enabled=1
gpgcheck=0
      REPO
    else
      raise ArgumentError, "Unsupported OS '#{os}'"
    end
  end
end
