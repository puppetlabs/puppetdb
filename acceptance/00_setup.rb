#!/usr/bin/env ruby

def get_tar_file(source_dir, prefix)
  # TODO: use Ruby's standard temp file mechanism
  `cd #{source_dir} && git archive --format=tar --prefix=#{prefix}/ HEAD -o /tmp/data.tar`
  '/tmp/data.tar'
end

test_name "Setup PuppetDB"

pkg_dir = File.join(File.dirname(__FILE__), '..', 'pkg')

# This is used for handling metrics
step "Install JSON on the PuppetDB server" do
  on database, "apt-get install -y libjson-ruby"
end

step "Install Puppet on all systems" do
  on hosts, "wget http://apt.puppetlabs.com/puppetlabs-release_1.0-3_all.deb"
  on hosts, "dpkg -i puppetlabs-release_1.0-3_all.deb"
  on hosts, "apt-get update"
  on hosts, "apt-get install --force-yes -y puppet"
end

step "Install PuppetDB on the PuppetDB server" do
  step "Run an agent to create the SSL certs" do
    with_master_running_on master, "--autosign true" do
      run_agent_on database, "--test --server #{master}"
    end
  end

  step "Copy the package" do
    on database, "mkdir -p /tmp/packages/puppetdb"
    scp_to database, File.join(pkg_dir, "puppetdb.deb"), "/tmp/packages/puppetdb"
  end

  step "Install the package" do
    on database, "dpkg -i /tmp/packages/puppetdb/puppetdb.deb", :acceptable_exit_codes => (0..255)
    on database, "apt-get -f -y install"
  end

  step "Start PuppetDB" do
    start_puppetdb(database)
  end
end

step "Install the PuppetDB terminuses on the master" do
  step "Copy the package" do
    on master, "mkdir -p /tmp/packages/puppetdb"
    scp_to master, File.join(pkg_dir, "puppetdb-terminus.deb"), "/tmp/packages/puppetdb"
  end

  step "Install the package" do
    on master, "dpkg -i /tmp/packages/puppetdb/puppetdb-terminus.deb", :acceptable_exit_codes => (0..255)
    on master, "apt-get -f -y install"
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
