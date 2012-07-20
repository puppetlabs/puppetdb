#!/usr/bin/env ruby

require 'lib/puppet_acceptance/dsl/install_utils'

extend PuppetAcceptance::DSL::InstallUtils


LeinCommandPrefix = "cd /opt/puppet-git-repos/puppetdb; LEIN_ROOT=true"


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

# TODO: I'm not 100% sure whether this is the right or best way to determine
#  whether we're running from source or running from a package; might want
#  to change this.
install_type = options[:type] == 'git' ? :git : :package
PuppetDBExtensions.test_mode = install_type

pkg_dir = File.join(File.dirname(__FILE__), '..', '..', '..', 'pkg')

# Determine whether we're Debian or RedHat. Note that "git" installs are
# currently assumed to be *Debian only*.
on(database, "which yum", :silent => true)
if result.exit_code == 0
  osfamily = 'RedHat'
else
  osfamily = 'Debian'
end

# TODO: I think it would be nice to get rid of all of these conditionals; maybe
#  we could refactor this into a base class with two child classes that handle
#  the differences... or something less ugly than this :)
if (PuppetDBExtensions.test_mode == :package)
  step "Install Puppet on all systems" do
    if osfamily == 'Debian'
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
    on database, "apt-get install -y openjdk-6-jre-headless libjson-ruby"
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
      if osfamily == 'Debian'
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
      on database, "rm -rf /etc/puppetdb/ssl"
      on database, "#{LeinCommandPrefix} rake template"
      on database, "sh /opt/puppet-git-repos/puppetdb/ext/files/debian/puppetdb.preinst install"
      on database, "#{LeinCommandPrefix} rake install"
      on database, "sh /opt/puppet-git-repos/puppetdb/ext/files/debian/puppetdb.postinst"
      # For debugging?
      on database, "cat /etc/puppetdb/conf.d/jetty.ini"
    end
  end

  if (options[:puppetdb_database] == 'postgres')
    setup_postgres()
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
    if osfamily == 'Debian'
      on master, "mkdir -p /tmp/packages/puppetdb"
      scp_to master, File.join(pkg_dir, "puppetdb-terminus.deb"), "/tmp/packages/puppetdb"

      on master, "dpkg -i /tmp/packages/puppetdb/puppetdb-terminus.deb", :acceptable_exit_codes => (0..255)
      on master, "apt-get -f -y install"
    else
      on master, "yum install --disablerepo puppetlabs-products -y puppetdb-terminus"
    end
  else
    step "Install the termini via rake" do
      on master, "#{LeinCommandPrefix} rake sourceterminus"
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
server = #{database}
port = 8081
    CONF

    puppetdb_conf_file = File.join(master['puppetpath'], 'puppetdb.conf')

    create_remote_file(master, puppetdb_conf_file, puppetdb_conf)

    on master, "chmod +r #{puppetdb_conf_file}"
  end
end
