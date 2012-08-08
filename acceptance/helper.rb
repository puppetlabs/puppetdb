#!/usr/bin/env ruby

require 'cgi'

module PuppetDBExtensions

  def self.initialize_test_config(options, os_families, db_module_path)
    install_type =
        case options[:type]
          when 'git'
            :git
          when 'manual':
            :package
          else
            raise ArgumentError, "Unsupported install type '#{options[:type]}'"
        end

    database =
        case options[:puppetdb_database]
          when 'postgres'
            :postgres
          when 'embedded'
            :embedded
          else
            raise ArgumentError, "Unsupported database '#{options[:puppetdb_database]}'"
        end

    @config = {
        :install_type => install_type,
        :pkg_dir => File.join(File.dirname(__FILE__), '..', '..', '..', 'pkg'),
        :os_families => os_families,
        :db_module_path => db_module_path,
        :database => database,
        :install_mode => options[:puppetdb_install_mode],
    }
  end

  class << self
    attr_reader :config
  end

  def get_os_family(host)
    on(host, "which yum", :silent => true)
    if result.exit_code == 0
      :redhat
    else
      :debian
    end
  end


  def puppetdb_confdir(host)
    if host.is_pe?
      "/etc/puppetlabs/puppetdb"
    else
      "/etc/puppetdb"
    end
  end

  def start_puppetdb(host)
    step "Starting PuppetDB" do
      on host, "service puppetdb start"
      sleep_until_started(host)
    end
  end

  def sleep_until_started(host)
    # Omit 127 because it means "command not found".
    on host, "curl http://localhost:8080", :acceptable_exit_codes => (0...127)
    num_retries = 0
    until exit_code == 0
      sleep 1
      on host, "curl http://localhost:8080", :acceptable_exit_codes => (0...127)
      num_retries += 1
      if (num_retries > 60)
        fail("Unable to start puppetdb")
      end
    end
  end


  def install_puppetdb(host)
    os = PuppetDBExtensions.config[:os_families][host.name]
    case os
    when :debian
      on host, "yes | apt-get install -y --force-yes puppetdb"
    when :redhat
      on host, "yum install -y puppetdb"
    else
      raise ArgumentError, "Unsupported OS family: '#{os}'"
    end
  end

  def validate_package_version(host)
    step "Verifying package version" do
      os = PuppetDBExtensions.config[:os_families][host.name]
      installed_version =
        case os
          when :debian
            result = on host, "dpkg-query --showformat \"\\${Version}\" --show puppetdb"
            result.stdout.strip
          when :redhat
            result = on host, "rpm -q puppetdb --queryformat \"%{VERSION}-%{RELEASE}\""
            result.stdout.strip.split('.')[0...-1].join('.')
          else
            raise ArgumentError, "Unsupported OS family: '#{os}'"
        end
      PuppetAcceptance::Log.notify "Expecting package version: '#{ENV['PUPPETDB_EXPECTED_VERSION']}', actual version: '#{installed_version}'"
      assert_equal(installed_version, ENV['PUPPETDB_EXPECTED_VERSION'])
    end
  end

  def configure_puppetdb(host)
    step "Configure database.ini file" do
      manifest_path = host.tmpfile("puppetdb_manifest.pp")
      # TODO: use more stuff from the puppetdb module once it is robust enough
      #  to handle what we need.
      manifest_content = <<-EOS
  $database = '#{PuppetDBExtensions.config[:database]}'
  $database_host = 'localhost'

  file { 'puppetdb: database.ini':
      ensure      => file,
      path        => "/etc/puppetdb/conf.d/database.ini",
      content     => template('puppetdb/server/database.ini.erb')
  }
      EOS

      create_remote_file(host, manifest_path, manifest_content)
      on host, puppet_apply("--modulepath #{PuppetDBExtensions.config[:db_module_path]} #{manifest_path}")
    end

    step "Print out jetty.ini for posterity" do
      on host, "cat /etc/puppetdb/conf.d/jetty.ini"
    end
    step "Print out database.ini for posterity" do
      on host, "cat /etc/puppetdb/conf.d/database.ini"
    end

  end

  def install_puppetdb_termini(host)
    os = PuppetDBExtensions.config[:os_families][host.name]
    case os
    when :debian
      on host, "apt-get install -y --force-yes puppetdb-terminus"
    when :redhat
      on host, "yum install -y puppetdb-terminus"
    else
      raise ArgumentError, "Unsupported OS family: '#{os}'"
    end
  end

  def configure_termini(master, database)
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
server = #{database.name}
port = 8081
      CONF

      puppetdb_conf_file = File.join(master['puppetpath'], 'puppetdb.conf')

      create_remote_file(master, puppetdb_conf_file, puppetdb_conf)

      on master, "chmod +r #{puppetdb_conf_file}"
    end
  end



  def stop_puppetdb(host)
    on host, "service puppetdb stop"
    sleep_until_stopped(host)
  end

  def sleep_until_stopped(host)
    on host, "curl http://localhost:8080", :acceptable_exit_codes => (0...127)
    num_retries = 0
    until exit_code == 7
      sleep 1
      on host, "curl http://localhost:8080", :acceptable_exit_codes => (0...127)
      num_retries += 1
      if (num_retries > 60)
        fail("Unable to stop puppetdb")
      end
    end
  end

  def restart_puppetdb(host)
    stop_puppetdb(host)
    start_puppetdb(host)
  end

  def sleep_until_queue_empty(host, timeout=nil)
    metric = "org.apache.activemq:BrokerName=localhost,Type=Queue,Destination=com.puppetlabs.puppetdb.commands"
    queue_size = nil

    begin
      Timeout.timeout(timeout) do
        until queue_size == 0
          result = on host, %Q(curl -H 'Accept: application/json' http://localhost:8080/metrics/mbean/#{CGI.escape(metric)} 2> /dev/null |awk -F"," '{for (i = 1; i <= NF; i++) { print $i } }' |grep QueueSize |awk -F ":" '{ print $2 }')
          queue_size = Integer(result.stdout.chomp)
        end
      end
    rescue Timeout::Error => e
      raise "Queue took longer than allowed #{timeout} seconds to empty"
    end
  end
end

PuppetAcceptance::TestCase.send(:include, PuppetDBExtensions)
