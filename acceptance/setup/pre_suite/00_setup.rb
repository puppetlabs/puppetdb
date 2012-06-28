#!/usr/bin/env ruby

LeinCommandPrefix = "cd /opt/puppet-git-repos/puppetdb; LEIN_ROOT=true"

def get_tar_file(source_dir, prefix)
  # TODO: use Ruby's standard temp file mechanism
  `cd #{source_dir} && git archive --format=tar --prefix=#{prefix}/ HEAD -o /tmp/data.tar`
  '/tmp/data.tar'
end

test_name "Setup PuppetDB"

# TODO: I'm not 100% sure whether this is the right or best way to determine
#  whether we're running from source or running from a package; might want
#  to change this.
install_type = options[:type] == 'git' ? :git : :package
PuppetDBExtensions.test_mode = install_type


pkg_dir = File.join(File.dirname(__FILE__), '..', 'pkg')

# This is used for handling metrics
step "Install JSON on the PuppetDB server" do
  on database, "apt-get install -y libjson-ruby"
end

# TODO: I think it would be nice to get rid of all of these conditionals; maybe
#  we could refactor this into a base class with two child classes that handle
#  the differences... or something less ugly than this :)
if (PuppetDBExtensions.test_mode == :git)
  step "Install rake on the PuppetDB server" do
    on database, "apt-get install -y rake"
  end
  step "Install java on the PuppetDB server" do
    on database, "apt-get install -y openjdk-6-jre-headless"
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

if (PuppetDBExtensions.test_mode == :package)
  step "Install Puppet on all systems" do
    on hosts, "wget http://apt.puppetlabs.com/puppetlabs-release_1.0-3_all.deb"
    on hosts, "dpkg -i puppetlabs-release_1.0-3_all.deb"
    on hosts, "apt-get update"
    on hosts, "apt-get install --force-yes -y puppet"
  end
end

step "Install PuppetDB on the PuppetDB server" do
  step "Run an agent to create the SSL certs" do
    with_master_running_on master, "--autosign true" do
      run_agent_on database, "--test --server #{master}"
    end
  end

  if (PuppetDBExtensions.test_mode == :package)
    step "Copy the package" do
      on database, "mkdir -p /tmp/packages/puppetdb"
      scp_to database, File.join(pkg_dir, "puppetdb.deb"), "/tmp/packages/puppetdb"
    end

    step "Install the package" do
      on database, "dpkg -i /tmp/packages/puppetdb/puppetdb.deb", :acceptable_exit_codes => (0..255)
      on database, "apt-get -f -y install"
    end
  else
    step "Install PuppetDB via rake" do
      on database, "rm -rf /etc/puppetdb/ssl"
      on database, "#{LeinCommandPrefix} rake template"
      on database, "sh /opt/puppet-git-repos/puppetdb/ext/files/debian/puppetdb.preinst install"
      on database, "#{LeinCommandPrefix} rake install"
      # TODO: might want to move this into teardown, but if it doesn't happen
      #  at some point then you will end up with a bad jetty.ini file.
      on database, "sh /opt/puppet-git-repos/puppetdb/ext/files/debian/puppetdb.postinst"
      on database, "cat /etc/puppetdb/conf.d/jetty.ini"

    end
  end

  step "Start PuppetDB" do
    start_puppetdb(database)
  end
end

step "Install the PuppetDB terminuses on the master" do
  if (PuppetDBExtensions.test_mode == :package)
    step "Copy the package" do
      on master, "mkdir -p /tmp/packages/puppetdb"
      scp_to master, File.join(pkg_dir, "puppetdb-terminus.deb"), "/tmp/packages/puppetdb"
    end

    step "Install the package" do
      on master, "dpkg -i /tmp/packages/puppetdb/puppetdb-terminus.deb", :acceptable_exit_codes => (0..255)
      on master, "apt-get -f -y install"
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
