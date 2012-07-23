#!/usr/bin/env ruby

require 'lib/puppet_acceptance/dsl/install_utils'

extend PuppetAcceptance::DSL::InstallUtils


LEIN_COMMAND_PREFIX = "cd /opt/puppet-git-repos/puppetdb; LEIN_ROOT=true"
PROXY_URL = "http://modi.puppetlabs.lan:3128"


test_name "Setup PuppetDB"



def get_tar_file(source_dir, prefix)
  # TODO: use Ruby's standard temp file mechanism
  `cd #{source_dir} && git archive --format=tar --prefix=#{prefix}/ HEAD -o /tmp/data.tar`
  '/tmp/data.tar'
end

def setup_postgres()
  step "Install postgres on the PuppetDB server" do

    step "Install the puppetdb/postgres modules and dependencies" do
      # TODO: change this to install from a less stupid place once the
      #  puppetdb module gets cleaned up
      install_from_git(database, "puppetlabs-puppetdb",
                       "git://github.com/cprice-puppet/puppetlabs-puppetdb.git",
                       "master")

      # TODO: change this to install from a less stupid place once the
      #  postgres module gets cleaned up
      install_from_git(database, "puppet-postgresql",
                       "git://github.com/cprice-puppet/puppet-postgresql.git",
                       "feature/master/align-with-puppetlabs-mysql")

      # TODO: change this to install from a tag once we do a 2.3.4 stdlib release
      install_from_git(database, "puppetlabs-stdlib",
                       "git://github.com/puppetlabs/puppetlabs-stdlib.git",
                       "e299ac6212b0468426f971b216447ef6bc679149")

      install_from_git(database, "puppetlabs-firewall",
                       "git://github.com/puppetlabs/puppetlabs-firewall.git",
                       "master")
    end

    step "Apply a manifest to use the modules to set up postgres for puppetdb" do
      module_path = database.tmpfile("puppetdb_modulepath")
      on database, "rm #{module_path}"
      on database, "mkdir #{module_path}"
      on database, "ln -s #{SourcePath}/puppetlabs-puppetdb #{module_path}/puppetdb"
      on database, "ln -s #{SourcePath}/puppet-postgresql #{module_path}/postgresql"
      on database, "ln -s #{SourcePath}/puppetlabs-stdlib #{module_path}/stdlib"
      on database, "ln -s #{SourcePath}/puppetlabs-firewall #{module_path}/firewall"

      manifest_path = database.tmpfile("puppetdb_postgres_manifest.pp")
      # TODO: use more stuff from the puppetdb module once it is robust enough
      #  to handle what we need.
      manifest_content = <<-EOS

  class { '::postgresql::server':
    config_hash => {
        'ip_mask_allow_all_users' => '0.0.0.0/0',
        'listen_addresses' => '*',
        'manage_redhat_firewall' => false,

        'ip_mask_deny_postgres_user' => '0.0.0.0/32',
        'postgres_password' => 'puppet',
    },
  }

  postgresql::db{ 'puppetdb':
    user          => 'puppetdb',
    password      => 'puppetdb',
    grant         => 'all',
  }

  $database = 'postgres'
  $database_host = 'localhost'

  file { 'puppetdb: database.ini':
      ensure      => file,
      path        => "/etc/puppetdb/conf.d/database.ini",
      content     => template('puppetdb/server/database.ini.erb')
  }
      EOS

      create_remote_file(database, manifest_path, manifest_content)
      on database, puppet_apply("--modulepath #{module_path} #{manifest_path}")
    end
  end
end

def setup_maven_squid_proxy()
  on(database, "mkdir -p /root/.m2")

  m2_settings_path = "/root/.m2/settings.xml"
  m2_settings_content = <<-EOS
<settings>
    <proxies>
        <proxy>
            <active>true</active>
            <protocol>http</protocol>
            <host>modi.puppetlabs.lan</host>
            <port>3128</port>
        </proxy>
    </proxies>
</settings>
  EOS

  create_remote_file(database, m2_settings_path, m2_settings_content)
end


def setup_apt_proxy()
    step "Configure apt to use local http proxy" do
      apt_conf_file_path = "/etc/apt/apt.conf.d/99apt-http-proxy"
      apt_conf_file_content = <<-EOS
// Configure apt to use a local http proxy
Acquire::http::Proxy "#{PROXY_URL}";
    EOS

    create_remote_file(database, apt_conf_file_path, apt_conf_file_content)
  end
end

def setup_yum_proxy()
  step "Configure yum to use local http proxy" do

    existing_yumconf = on(database, "cat /etc/yum.conf").stdout
    new_yumconf_lines = []
    existing_yumconf.each_line do |line|
      # filter out existing proxy line if there is one.
      unless line =~ /^\s*proxy\s*=/
        new_yumconf_lines << line
      end
    end
    new_yumconf_lines << "proxy=#{PROXY_URL}\n"
    on(database, "mv /etc/yum.conf /etc/yum.conf.bak-puppet_acceptance")
    create_remote_file(database, "/etc/yum.conf", new_yumconf_lines.join)
  end
end


# TODO: I'm not 100% sure whether this is the right or best way to determine
#  whether we're running from source or running from a package; might want
#  to change this.
install_type = options[:type] == 'git' ? :git : :package
PuppetDBExtensions.test_mode = install_type

pkg_dir = File.join(File.dirname(__FILE__), '..', '..', '..', 'pkg')
osfamily = :debian

# TODO: do we need to worry about supporting any other OS's?
step "Determine whether we're Debian or RedHat" do
  on(database, "which yum", :silent => true)
  if result.exit_code == 0
    osfamily = :redhat
  end
end


step "Configure package manager to use local http proxy" do
  # TODO: this should probably run on every host, not just on the database host,
  #  and it should probably be moved into the main acceptance framework instead
  #  of being used only for our project.

  if (osfamily == :debian)
    setup_apt_proxy()
  else
    setup_yum_proxy()
  end
end



# TODO: I think it would be nice to get rid of all of these conditionals; maybe
#  we could refactor this into a base class with two child classes that handle
#  the differences... or something less ugly than this :)
if (PuppetDBExtensions.test_mode == :package)
  step "Install Puppet on all systems" do
    if osfamily == :debian
      on hosts, "wget http://apt.puppetlabs.com/puppetlabs-release-$(lsb_release -sc).deb"
      on hosts, "dpkg -i puppetlabs-release-$(lsb_release -sc).deb"
      on hosts, "apt-get update"
      on hosts, "apt-get install --force-yes -y puppet"
    else
      create_remote_file hosts, '/etc/yum.repos.d/puppetlabs-products.repo', <<-REPO.gsub(' '*6, '')
      [puppetlabs-products]
      name=Puppet Labs Products - $basearch
      baseurl=http://yum.puppetlabs.com/el/$releasever/products/$basearch
      gpgkey=http://yum.puppetlabs.com/RPM-GPG-KEY-puppetlabs
      enabled=1
      gpgcheck=1
      REPO

      create_remote_file database, '/etc/yum.repos.d/puppetlabs-prerelease.repo', <<-REPO.gsub(' '*6, '')
      [puppetlabs-prerelease]
      name=Puppet Labs Prerelease - $basearch
      baseurl=http://neptune.puppetlabs.lan/dev/el/$releasever/products/$basearch
      enabled=1
      gpgcheck=0
      REPO

      on hosts, "yum install -y puppet"
    end
  end
else (PuppetDBExtensions.test_mode == :git)
  step "Install dependencies on the PuppetDB server" do
    if osfamily == :debian
      on database, "apt-get install -y openjdk-6-jre-headless libjson-ruby"
    else
      on database, "yum install -y java-1.6.0-openjdk rubygem-rake unzip"
    end
  end

  step "Configure maven to use local squid proxy" do
    setup_maven_squid_proxy()
  end

  step "Install lein on the PuppetDB server" do
    which_result = on database, "which lein", :acceptable_exit_codes => [0,1]
    needs_lein = which_result.exit_code == 1
    if (needs_lein)
      on database, "curl -k https://raw.github.com/technomancy/leiningen/1.7.1/bin/lein -o /usr/local/bin/lein"
      on database, "chmod +x /usr/local/bin/lein"
      on database, "LEIN_ROOT=true lein"
    end
  end
end

step "Run an agent to create the SSL certs" do
  with_master_running_on master, "--autosign true" do
    run_agent_on database, "--test --server #{master}"
  end
end

step "Install PuppetDB on the PuppetDB server" do
  if (PuppetDBExtensions.test_mode == :package)
    step "Install the package" do
      if osfamily == :debian
        on database, "apt-get install -y libjson-ruby"
        on database, "mkdir -p /tmp/packages/puppetdb"
        scp_to database, File.join(pkg_dir, "puppetdb.deb"), "/tmp/packages/puppetdb"
        on database, "dpkg -i /tmp/packages/puppetdb/puppetdb.deb", :acceptable_exit_codes => (0..255)
        on database, "apt-get -f -y install"
      else
        on database, "yum install -y ruby-json"
        on database, "yum install --disablerepo puppetlabs-products -y puppetdb"
      end
    end
  else
    step "Install PuppetDB via rake" do
      if (osfamily == :debian)
        preinst = "debian/puppetdb.preinst install"
        postinst = "debian/puppetdb.postinst"
      else
        preinst = "dev/redhat/redhat_dev_preinst install"
        postinst = "dev/redhat/redhat_dev_postinst install"
      end

      on database, "rm -rf /etc/puppetdb/ssl"
      on database, "#{LEIN_COMMAND_PREFIX} rake template"
      on database, "sh /opt/puppet-git-repos/puppetdb/ext/files/#{preinst}"
      on database, "#{LEIN_COMMAND_PREFIX} rake install"
      on database, "sh /opt/puppet-git-repos/puppetdb/ext/files/#{postinst}"
    end
  end

  if (options[:puppetdb_database] == 'postgres')
    setup_postgres()
  end

  step "Print out jetty.ini for posterity" do
    on database, "cat /etc/puppetdb/conf.d/jetty.ini"
  end
  step "Print out database.ini for posterity" do
    on database, "cat /etc/puppetdb/conf.d/database.ini"
  end

  step "Start PuppetDB" do
    start_puppetdb(database)
  end
end

step "Install the PuppetDB terminuses on the master" do
  if (PuppetDBExtensions.test_mode == :package)
    if osfamily == :debian
      on master, "mkdir -p /tmp/packages/puppetdb"
      scp_to master, File.join(pkg_dir, "puppetdb-terminus.deb"), "/tmp/packages/puppetdb"

      on master, "dpkg -i /tmp/packages/puppetdb/puppetdb-terminus.deb", :acceptable_exit_codes => (0..255)
      on master, "apt-get -f -y install"
    else
      on master, "yum install --disablerepo puppetlabs-products -y puppetdb-terminus"
    end
  else
    step "Install the termini via rake" do
      on master, "#{LEIN_COMMAND_PREFIX} rake sourceterminus"
    end
  end

  step "Configure routes.yaml to talk to the PuppetDB server" do
    routes = <<-ROUTES
master:
  catalog:
    terminus: compiler
    cache: puppetdb
  facts:
    terminus: puppetdb
    cache: yaml
    ROUTES

    routes_file = File.join(master['puppetpath'], 'routes.yaml')

    create_remote_file(master, routes_file, routes)

    on master, "chmod +r #{routes_file}"
  end

  step "Configure puppetdb.conf to point to the PuppetDB server" do
    puppetdb_conf = <<-CONF
[main]
server = #{database.node_name}
port = 8081
    CONF

    puppetdb_conf_file = File.join(master['puppetpath'], 'puppetdb.conf')

    create_remote_file(master, puppetdb_conf_file, puppetdb_conf)

    on master, "chmod +r #{puppetdb_conf_file}"
  end
end
