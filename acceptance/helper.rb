#!/usr/bin/env ruby

require 'cgi'
require 'lib/puppet_acceptance/dsl/install_utils'
require 'pp'

module PuppetDBExtensions

  GitReposDir = PuppetAcceptance::DSL::InstallUtils::SourcePath

  LeinCommandPrefix = "cd #{GitReposDir}/puppetdb; LEIN_ROOT=true"

  def self.initialize_test_config(options, os_families)

    base_dir = File.join(File.dirname(__FILE__), '..')

    install_type =
        get_option_value(options[:type], [:git, :manual], "install type")

    install_mode =
        get_option_value(options[:puppetdb_install_mode],
                         [:install, :upgrade], "install mode",
                         "PUPPETDB_INSTALL_MODE", :install)

    database =
        get_option_value(options[:puppetdb_database],
            [:postgres, :embedded], "database", "PUPPETDB_DATABASE", :postgres)

    validate_package_version =
        get_option_value(options[:puppetdb_validate_package_version],
            [:true, :false], "'validate package version'",
            "PUPPETDB_VALIDATE_PACKAGE_VERSION", :true)

    expected_package_version =
        get_option_value(options[:puppetdb_expected_package_version],
            nil, "'expected package version'",
            "PUPPETDB_EXPECTED_PACKAGE_VERSION", nil)

    use_proxies =
        get_option_value(options[:puppetdb_use_proxies],
          [:true, :false], "'use proxies'", "PUPPETDB_USE_PROXIES", :true)


    package_repo_url =
        get_option_value(options[:puppetdb_package_repo_url],
          nil,
          "'base URL for yum/apt repos'",
          "PUPPETDB_PACKAGE_REPO_URL",
          "http://neptune.puppetlabs.lan/dev/puppetdb/master")


    @config = {
        :base_dir => base_dir,
        :acceptance_data_dir => File.join(base_dir, "acceptance", "data"),
        :os_families => os_families,
        :install_type => install_type == :manual ? :package : install_type,
        :install_mode => install_mode,
        :database => database,
        :validate_package_version => validate_package_version == :true,
        :expected_package_version => expected_package_version,
        :use_proxies => use_proxies == :true,
        :package_repo_url => package_repo_url,
    }

    pp_config = PP.pp(@config, "")

    PuppetAcceptance::Log.notify "PuppetDB Acceptance Configuration:\n\n#{pp_config}\n\n"
  end

  class << self
    attr_reader :config
  end


  def self.get_option_value(value, legal_values, description,
      env_var_name = nil, default_value = nil)

    # we give precedence to any value explicitly specified in an options file,
    #  but we also allow environment variables to be used for
    #  puppetdb-specific settings
    value = (value || (env_var_name && ENV[env_var_name]) || default_value)
    if value
      value = value.to_sym
    end

    unless legal_values.nil? or legal_values.include?(value)
      raise ArgumentError, "Unsupported #{description} '#{value}'"
    end

    value
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

  def get_package_version(host, version)
    # These 'platform' values come from the acceptance config files, so
    # we're relying entirely on naming conventions here.  Would be nicer
    # to do this using lsb_release or something, but...
    if host['platform'].include?('el-5')
      version + "-1.el5"
    elsif host['platform'].include?('el-6')
      version + "-1.el6"
    elsif host['platform'].include?('ubuntu') or host['platform'].include?('debian')
      version + "-1puppetlabs1"
    else
      raise ArgumentError, "Unsupported platform: '#{host['platform']}'"
    end
  end


  def install_puppetdb(host, db, version='latest')
    manifest_path = host.tmpfile("puppetdb_manifest.pp")
    manifest_content = <<-EOS
    class { 'puppetdb':
      database               => '#{db}',
      manage_redhat_firewall => false,
      puppetdb_version       => '#{get_package_version(host, version)}',
    }
    EOS
    create_remote_file(host, manifest_path, manifest_content)
    on host, puppet_apply("#{manifest_path}")
    print_ini_files(host)
    sleep_until_started(host)
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
            result.stdout.strip
          else
            raise ArgumentError, "Unsupported OS family: '#{os}'"
        end
      expected_version = get_package_version(host, ENV['PUPPETDB_EXPECTED_VERSION'])
      PuppetAcceptance::Log.notify "Expecting package version: '#{expected_version}', actual version: '#{installed_version}'"
      if installed_version != expected_version
        raise RuntimeError, "Installed version '#{installed_version}' did not match expected version '#{expected_version}'"
      end
    end
  end


  def install_puppetdb_termini(host, database, version='latest')
    manifest_path = host.tmpfile("puppetdb_manifest.pp")
    # We pass 'restart_puppet' => false to prevent the module from trying to
    # manage the puppet master service, which isn't actually installed on the
    # acceptance nodes (they run puppet master from the CLI).
    manifest_content = <<-EOS
    class { 'puppetdb::master::config':
      puppetdb_server           => '#{database.node_name}',
      puppetdb_version          => '#{get_package_version(host, version)}',
      puppetdb_startup_timeout  => 120,
      restart_puppet            => false,
    }
    EOS
    create_remote_file(host, manifest_path, manifest_content)
    on host, puppet_apply("#{manifest_path}")
  end


  def print_ini_files(host)
    step "Print out jetty.ini for posterity" do
      on host, "cat /etc/puppetdb/conf.d/jetty.ini"
    end
    step "Print out database.ini for posterity" do
      on host, "cat /etc/puppetdb/conf.d/database.ini"
    end
  end

  ############################################################################
  # NOTE: the following methods should only be called during run-from-source
  #  acceptance test runs.
  ############################################################################

  def install_postgres(host)
    PuppetAcceptance::Log.notify "Installing postgres on #{host}"

    on host, puppet_apply("-e 'include puppetdb::database::postgresql'")
  end

  def install_puppetdb_via_rake(host)
    os = PuppetDBExtensions.config[:os_families][host.name]
    case os
      when :debian
        preinst = "debian/puppetdb.preinst install"
        postinst = "debian/puppetdb.postinst"
      when :redhat
        preinst = "dev/redhat/redhat_dev_preinst install"
        postinst = "dev/redhat/redhat_dev_postinst install"
      else
        raise ArgumentError, "Unsupported OS family: '#{os}'"
    end

    on host, "rm -rf /etc/puppetdb/ssl"
    on host, "#{LeinCommandPrefix} rake template"
    on host, "sh #{GitReposDir}/puppetdb/ext/files/#{preinst}"
    on host, "#{LeinCommandPrefix} rake install"
    on host, "sh #{GitReposDir}/puppetdb/ext/files/#{postinst}"

    step "Configure database.ini file" do
      manifest_path = host.tmpfile("puppetdb_manifest.pp")
      manifest_content = <<-EOS
  $database = '#{PuppetDBExtensions.config[:database]}'

  class { 'puppetdb::server::database_ini':
      database      => $database,
  }
      EOS

      create_remote_file(host, manifest_path, manifest_content)
      on host, puppet_apply("#{manifest_path}")
    end

    print_ini_files(host)
  end

  def install_puppetdb_termini_via_rake(host, database)
    on host, "#{LeinCommandPrefix} rake sourceterminus"
    manifest_path = host.tmpfile("puppetdb_manifest.pp")
    manifest_content = <<-EOS
    include puppetdb::master::storeconfigs
    class { 'puppetdb::master::puppetdb_conf':
      server => '#{database.node_name}',
    }
    include puppetdb::master::routes
    EOS
    create_remote_file(host, manifest_path, manifest_content)
    on host, puppet_apply("#{manifest_path}")
  end

  ###########################################################################


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
