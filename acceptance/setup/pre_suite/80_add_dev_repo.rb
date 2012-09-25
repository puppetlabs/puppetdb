test_config = PuppetDBExtensions.config
os = test_config[:os_families][database.name]


if (test_config[:install_type] == :package)

  step "Add development repository on PuppetDB server" do
    case os
    when :debian
      # TODO: this could be (maybe?) ported over to use the puppetlabs-apt module.
      on database, "echo deb http://apt-dev.puppetlabs.lan $(lsb_release -sc) main >> /etc/apt/sources.list"
      on database, "curl http://apt-dev.puppetlabs.lan/pubkey.gpg |apt-key add -"
      on database, "apt-get update"
    when :redhat
      create_remote_file database, '/etc/yum.repos.d/puppetlabs-prerelease.repo', <<-REPO.gsub(' '*6, '')
  [puppetlabs-development]
  name=Puppet Labs Development - $basearch
  baseurl=http://neptune.puppetlabs.lan/dev/el/$releasever/products/$basearch
  enabled=1
  gpgcheck=0
      REPO
    else
      raise ArgumentError, "Unsupported OS '#{os}'"
    end
  end
end
