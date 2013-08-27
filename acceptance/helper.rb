#!/usr/bin/env ruby

require 'cgi'
require 'lib/puppet_acceptance/dsl/install_utils'
require 'pp'
require 'set'
require 'test/unit/assertions'
require 'json'

module PuppetDBExtensions
  include PuppetAcceptance::Assertions

  GitReposDir = PuppetAcceptance::DSL::InstallUtils::SourcePath

  LeinCommandPrefix = "cd #{GitReposDir}/puppetdb; LEIN_ROOT=true"

  def self.initialize_test_config(options, os_families)

    base_dir = File.join(File.dirname(__FILE__), '..')

    install_type =
        get_option_value(options[:type], [:git, :manual, :pe], "install type")

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

    expected_rpm_version =
        get_option_value(options[:puppetdb_expected_rpm_version],
            nil, "'expected RPM package version'",
            "PUPPETDB_EXPECTED_RPM_VERSION", nil)

    expected_deb_version =
        get_option_value(options[:puppetdb_expected_deb_version],
                         nil, "'expected DEB package version'",
                         "PUPPETDB_EXPECTED_DEB_VERSION", nil)

    use_proxies =
        get_option_value(options[:puppetdb_use_proxies],
          [:true, :false], "'use proxies'", "PUPPETDB_USE_PROXIES", :true)

    purge_after_run =
        get_option_value(options[:puppetdb_purge_after_run],
          [:true, :false],
          "'purge packages and perform exhaustive cleanup after run'",
          "PUPPETDB_PURGE_AFTER_RUN", :false)

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
        :expected_rpm_version => expected_rpm_version,
        :expected_deb_version => expected_deb_version,
        :use_proxies => use_proxies == :true,
        :purge_after_run => purge_after_run == :true,
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

  def puppetdb_sharedir(host)
    if host.is_pe?
      "/opt/puppet/share/puppetdb"
    else
      "/usr/share/puppetdb"
    end
  end

  def puppetdb_sbin_dir(host)
    if host.is_pe?
      "/opt/puppet/sbin"
    else
      "/usr/sbin"
    end
  end

  def start_puppetdb(host)
    step "Starting PuppetDB" do
      if host.is_pe?
        on host, "service pe-puppetdb start"
      else
        on host, "service puppetdb start"
      end
      sleep_until_started(host)
    end
  end

  def sleep_until_started(host)
    curl_with_retries("start puppetdb", host, "http://localhost:8080", 0, 120)
    curl_with_retries("start puppetdb (ssl)",
                      host, "https://#{host.node_name}:8081", [35, 60])
  end

  def get_package_version(host, version = nil)
    return version unless version.nil?

    ## These 'platform' values come from the acceptance config files, so
    ## we're relying entirely on naming conventions here.  Would be nicer
    ## to do this using lsb_release or something, but...
    if host['platform'].include?('el-5')
      "#{PuppetDBExtensions.config[:expected_rpm_version]}.el5"
    elsif host['platform'].include?('el-6')
      "#{PuppetDBExtensions.config[:expected_rpm_version]}.el6"
    elsif host['platform'].include?('ubuntu') or host['platform'].include?('debian')
      "#{PuppetDBExtensions.config[:expected_deb_version]}"
    else
      raise ArgumentError, "Unsupported platform: '#{host['platform']}'"
    end

  end


  def install_puppetdb(host, db, version=nil)
    manifest = <<-EOS
    class { 'puppetdb':
      database               => '#{db}',
      open_ssl_listen_port   => false,
      open_postgres_port     => false,
      puppetdb_version       => '#{get_package_version(host, version)}',
    }
    EOS
    apply_manifest_on(host, manifest)
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
      expected_version = get_package_version(host)

      PuppetAcceptance::Log.notify "Expecting package version: '#{expected_version}', actual version: '#{installed_version}'"
      if installed_version != expected_version
        raise RuntimeError, "Installed version '#{installed_version}' did not match expected version '#{expected_version}'"
      end
    end
  end


  def install_puppetdb_termini(host, database, version=nil)
    # We pass 'restart_puppet' => false to prevent the module from trying to
    # manage the puppet master service, which isn't actually installed on the
    # acceptance nodes (they run puppet master from the CLI).
    manifest = <<-EOS
    class { 'puppetdb::master::config':
      puppetdb_server           => '#{database.node_name}',
      puppetdb_version          => '#{get_package_version(host, version)}',
      puppetdb_startup_timeout  => 120,
      manage_report_processor   => true,
      enable_reports            => true,
      restart_puppet            => false,
    }
    EOS
    apply_manifest_on(host, manifest)
  end


  def print_ini_files(host)
    step "Print out jetty.ini for posterity" do
      on host, "cat /etc/puppetdb/conf.d/jetty.ini"
    end
    step "Print out database.ini for posterity" do
      on host, "cat /etc/puppetdb/conf.d/database.ini"
    end
  end


  def is_gem_installed_on?(host, gem)
    # Include a trailing space when grep'ing to force an exact match of the gem name,
    # so, for example, when checking for 'rspec' we don't match with 'rspec-core'.
    result = on host, "gem list #{gem} | grep \"#{gem} \"", :acceptable_exit_codes => [0,1]
    result.exit_code == 0
  end


  def current_time_on(host)
    result = on host, %Q|date --rfc-2822|
    CGI.escape(Time.rfc2822(result.stdout).iso8601)
  end

  ############################################################################
  # NOTE: the following methods should only be called during run-from-source
  #  acceptance test runs.
  ############################################################################

  def install_postgres(host)
    PuppetAcceptance::Log.notify "Installing postgres on #{host}"


    ############################################################################
    # NOTE: A lot of the differences between the PE and FOSS manifests here is 
    #   only necessary because the puppetdb::database::postgresql module
    #   doesn't parameterize things like the service name. It would be nice
    #   to simplify this once we've added more paramters to the module.
    ############################################################################

    if host.is_pe?
      service_name = "pe-postgresql"
      db_name = "pe-puppetdb"
      db_user = "pe-puppetdb"
      db_pass = "pe-puppetdb"
      manifest = <<-EOS
      # get the pg server up and running
      $version = '9.2'
      class { 'postgresql':
        client_package_name => 'pe-postgresql',
        server_package_name => 'pe-postgresql-server',
        devel_package_name  => 'pe-postgresql-devel',
        java_package_name   => 'pe-postgresql-jdbc',
        datadir             => "/opt/puppet/var/lib/pgsql/${version}/data",
        confdir             => "/opt/puppet/var/lib/pgsql/${version}/data",
        bindir              => '/opt/puppet/bin',
        service_name        => 'pe-postgresql',
        user                => 'pe-postgres',
        group               => 'pe-postgres',
        locale              => 'en_US.UTF8',
        charset             => 'UTF8',
        run_initdb          => true,
        version             => $version
      } ->
      class { '::postgresql::server':
        service_name => #{service_name},
        config_hash => {
          # TODO: make this stuff configurable
          'ip_mask_allow_all_users' => '0.0.0.0/0',
          'manage_redhat_firewall'  => false,
        },
      }
      # create the puppetdb database
      class { 'puppetdb::database::postgresql_db': 
        database_name     => #{db_name},
        database_username => #{db_user},
        database_password => #{db_pass},
      }
      EOS
    else
      manifest = <<-EOS
      class { 'puppetdb::database::postgresql':
        manage_redhat_firewall => false,
      }
      EOS
    end
    apply_manifest_on(host, manifest)
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
    on host, "#{LeinCommandPrefix} rake package:bootstrap"
    on host, "#{LeinCommandPrefix} rake template"
    on host, "sh #{GitReposDir}/puppetdb/ext/files/#{preinst}"
    on host, "#{LeinCommandPrefix} rake install"
    on host, "sh #{GitReposDir}/puppetdb/ext/files/#{postinst}"

    step "Configure database.ini file" do
      manifest = <<-EOS
  $database = '#{PuppetDBExtensions.config[:database]}'

  class { 'puppetdb::server::database_ini':
      database      => $database,
  }
      EOS

      apply_manifest_on(host, manifest)
    end

    print_ini_files(host)
  end

  def install_puppetdb_termini_via_rake(host, database)
    on host, "#{LeinCommandPrefix} rake sourceterminus"

    manifest = <<-EOS
    include puppetdb::master::storeconfigs
    class { 'puppetdb::master::puppetdb_conf':
      server => '#{database.node_name}',
    }
    class { 'puppetdb::master::report_processor':
      enable => true,
    }
    include puppetdb::master::routes
    EOS
    apply_manifest_on(host, manifest)
  end

  ###########################################################################


  def stop_puppetdb(host)
    if host.is_pe?
      on host, "service pe-puppetdb stop"
    else
      on host, "service puppetdb stop"
    end
    sleep_until_stopped(host)
  end

  def sleep_until_stopped(host)
    curl_with_retries("stop puppetdb", host, "http://localhost:8080", 7)
  end

  def restart_puppetdb(host)
    stop_puppetdb(host)
    start_puppetdb(host)
  end

  def clear_and_restart_puppetdb(host)
    stop_puppetdb(host)
    clear_database(host)
    start_puppetdb(host)
  end

  def sleep_until_queue_empty(host, timeout=nil)
    metric = "org.apache.activemq:BrokerName=localhost,Type=Queue,Destination=com.puppetlabs.puppetdb.commands"
    queue_size = nil

    begin
      Timeout.timeout(timeout) do
        until queue_size == 0
          result = on host, %Q(curl http://localhost:8080/v3/metrics/mbean/#{CGI.escape(metric)} 2> /dev/null |awk -F"," '{for (i = 1; i <= NF; i++) { print $i } }' |grep QueueSize |awk -F ":" '{ print $2 }')
          queue_size = Integer(result.stdout.chomp)
        end
      end
    rescue Timeout::Error => e
      raise "Queue took longer than allowed #{timeout} seconds to empty"
    end
  end

  def apply_manifest_on(host, manifest_content)
    manifest_path = host.tmpfile("puppetdb_manifest.pp")
    create_remote_file(host, manifest_path, manifest_content)
    PuppetAcceptance::Log.notify "Applying manifest on #{host}:\n\n#{manifest_content}"
    on host, puppet_apply("--detailed-exitcodes #{manifest_path}"), :acceptable_exit_codes => [0,2]
  end

  def curl_with_retries(desc, host, url, desired_exit_codes, max_retries = 60, retry_interval = 1)
    desired_exit_codes = [desired_exit_codes].flatten
    on host, "curl #{url}", :acceptable_exit_codes => (0...127)
    num_retries = 0
    until desired_exit_codes.include?(exit_code)
      sleep retry_interval
      on host, "curl #{url}", :acceptable_exit_codes => (0...127)
      num_retries += 1
      if (num_retries > max_retries)
        fail("Unable to #{desc}")
      end
    end
  end

  def clear_database(host)
    case PuppetDBExtensions.config[:database]
      when :postgres
        if host.is_pe?
          on host, 'su pe-postgres -c "/opt/puppet/bin/dropdb pe-puppetdb"'
        else
          on host, 'su postgres -c "dropdb puppetdb"'
        end
        install_postgres(host)
      when :embedded
        on host, "rm -rf #{puppetdb_sharedir(host)}/db/*"
      else
        raise ArgumentError, "Unsupported database: '#{PuppetDBExtensions.config[:database]}'"
    end
  end

  #########################################################
  # PuppetDB export utility functions
  #########################################################
  # These are for comparing puppetdb export tarballs.
  # This seems like a pretty ridiculous place to define them,
  # but there are no other obvious choices that I see at the
  # moment.  Should consider moving them to a ruby utility
  # code folder in the main PuppetDB source tree if such a
  # thing ever materializes.

  # @param export_file1 [String] path to first export file
  # @param export_file2 [String] path to second export file
  # @param opts [Hash] comparison options
  # @option opts [Boolean] :catalogs compare catalog? defaults to true
  # @option opts [Boolean] :metadata compare metadata? defaults to true
  # @option opts [Boolean] :reports compare reports? defaults to true
  def compare_export_data(export_file1, export_file2, opts={})
    # Apply defaults
    opts = {
      :catalogs => true,
      :metadata => true,
      :reports => true,
    }.merge(opts)

    # NOTE: I'm putting this tmpdir inside of cwd because I expect for that to
    #  be inside of the jenkins workspace, which I'm hoping means that it will
    #  be cleaned up regularly if we accidentally leave anything lying around
    tmpdir = "./puppetdb_export_test_tmp"
    FileUtils.rm_rf(tmpdir)
    export_dir1 = File.join(tmpdir, "export1", File.basename(export_file1, ".tar.gz"))
    export_dir2 = File.join(tmpdir, "export2", File.basename(export_file2, ".tar.gz"))
    FileUtils.mkdir_p(export_dir1)
    FileUtils.mkdir_p(export_dir2)

    `tar zxvf #{export_file1} -C #{export_dir1}`
    `tar zxvf #{export_file2} -C #{export_dir2}`

    export1_files = Set.new()
    Dir.glob("#{export_dir1}/**/*") do |f|
      relative_path = f.sub(/^#{export_dir1}\//, "")
      export1_files.add(relative_path)
      expected_path = File.join(export_dir2, relative_path)
      assert(File.exists?(expected_path), "Export file '#{export_file2}' is missing entry '#{relative_path}'")
      puts "Comparing file '#{relative_path}'"
      next if File.directory?(f)
      export_entry_type = get_export_entry_type(relative_path)
      case export_entry_type
        when :catalog
          compare_catalog(f, expected_path) if opts[:catalogs]
        when :metadata
          compare_metadata(f, expected_path) if opts[:metadata]
        when :report
          compare_report(f, expected_path) if opts[:reports]
        when :unknown
          fail("Unrecognized file found in archive: '#{relative_path}'")
      end
    end

    export2_files = Set.new(
      Dir.glob("#{export_dir2}/**/*").map { |f| f.sub(/^#{Regexp.escape(export_dir2)}\//, "") })
    diff = export2_files - export1_files

    assert(diff.empty?, "Export file '#{export_file2}' contains extra file entries: '#{diff.to_a.join("', '")}'")

    FileUtils.rm_rf(tmpdir)
  end

  def get_export_entry_type(path)
    case path
      when "puppetdb-bak/export-metadata.json"
        :metadata
      when /^puppetdb-bak\/catalogs\/.*\.json$/
        :catalog
      when /^puppetdb-bak\/reports\/.*\.json$/
        :report
      else
        :unknown
    end
  end


  def compare_catalog(cat1_path, cat2_path)
    cat1 = munge_catalog_for_comparison(cat1_path)
    cat2 = munge_catalog_for_comparison(cat2_path)

    diff = hash_diff(cat1, cat2)
    if (diff)
      diff = JSON.pretty_generate(diff)
    end

    assert(diff == nil, "Catalogs '#{cat1_path}' and '#{cat2_path}' don't match!' Diff:\n#{diff}")
  end

  def compare_report(cat1_path, cat2_path)
    cat1 = munge_report_for_comparison(cat1_path)
    cat2 = munge_report_for_comparison(cat2_path)

    diff = hash_diff(cat1, cat2)
    if (diff)
      diff = JSON.pretty_generate(diff)
    end

    assert(diff == nil, "Reports '#{cat1_path}' and '#{cat2_path}' don't match!' Diff:\n#{diff}")
  end

  def compare_metadata(meta1_path, meta2_path)
    meta1 = munge_metadata_for_comparison(meta1_path)
    meta2 = munge_metadata_for_comparison(meta2_path)

    diff = hash_diff(meta1, meta2)

    assert(diff == nil, "Export metadata does not match!  Diff\n#{diff}")
  end

  def munge_metadata_for_comparison(meta_path)
    meta = JSON.parse(File.read(meta_path))
    meta.delete("timestamp")
    meta
  end

  def munge_resource_for_comparison(resource)
    resource['tags'] = Set.new(resource['tags'])
    resource
  end

  def munge_catalog_for_comparison(cat_path)
    meta = JSON.parse(File.read(cat_path))
    munged_resources = meta["data"]["resources"].map { |resource| munge_resource_for_comparison(resource) }
    meta["data"]["resources"] = Set.new(munged_resources)
    meta["data"]["edges"] = Set.new(meta["data"]["edges"])
    meta
  end

  def munge_report_for_comparison(cat_path)
    JSON.parse(File.read(cat_path))
  end


  ############################################################################
  # NOTE: This code should be merged into the harness before long, and when
  #   that happens, we should get rid of this and use their version.
  #
  #   Temp copy of Justins new Puppet Master Methods
  ############################################################################

  class IniFile
    attr_accessor :contents
    def initialize file_as_string
      @contents = parse( file_as_string )
      @contents['main'] ||= {}
      @contents['master'] ||= {}
      @contents['agent'] ||= {}
    end

    def method_missing( meth, *args )
      if @contents.respond_to? meth
        @contents.send( meth, *args )
      else
        super
      end
    end

    def parse file_as_string
      accumulator = Hash.new
      accumulator[:global] = Hash.new
      section = :global
      file_as_string.each_line do |line|
        case line
        when /^\s*\[\S+\]/
          # We've got a section header
          match = line.match(/^\s*\[(\S+)\].*/)
          section = match[1]
          accumulator[section] = Hash.new
        when /^\s*\S+\s*=\s*\S/
          # add a key value pair to the current section
          # will add it to the :global section if before a section header
          # note: in line comments are not support in puppet.conf
          raw_key, raw_value = line.split( '=' )
          key = raw_key.strip
          value = raw_value.strip
          accumulator[section][key] = value
        end
        # comments, whitespace and lines without an '=' pass through
      end

      return accumulator
    end

    def to_s
      string = ''
      @contents.each_pair do |header, values|
        if header == :global
          values.each_pair do |key, value|
            next if value.nil?
            string << "#{key} = #{value}\n"
          end
          string << "\n"
        else
          string << "[#{header}]\n"
          values.each_pair do |key, value|
            next if value.nil?
            string << " #{key} = #{value}\n"
          end
          string << "\n"
        end
      end
      return string
    end
  end

  def puppet_conf_for host
    puppetconf = on( host, "cat #{host['puppetpath']}/puppet.conf" ).stdout
    IniFile.new( puppetconf )
  end

  def with_puppet_running_on host, conf_opts, testdir = host.tmpdir(File.basename(@path)), &block
    new_conf = puppet_conf_for( host )
    new_conf.contents.each_key do |key|
      new_conf.contents[key].merge!( conf_opts.delete( key ) ) if conf_opts[key]
    end
    new_conf.contents.merge!( conf_opts )
    create_remote_file host, "#{testdir}/puppet.conf", new_conf.to_s
    # puts "#########################"
    # puts "New conf = #{new_conf.to_s}"

    begin
      on host, "cp #{host['puppetpath']}/puppet.conf #{host['puppetpath']}/puppet.conf.bak"
      on host, "cat #{testdir}/puppet.conf > #{host['puppetpath']}/puppet.conf", :silent => true
      on host, "cat #{host['puppetpath']}/puppet.conf"
      if host.is_pe?
        on host, '/etc/init.d/pe-httpd restart' # we work with PE yo!
      else
        on host, puppet( 'master' ) # maybe we even work with FOSS?!?!??
        require 'socket'
        inc = 0
        logger.debug 'Waiting for the puppet master to start'
        begin
          TCPSocket.new(host['ip'] || host.to_s, 8140).close
        rescue Errno::ECONNREFUSED
          sleep 1
          inc += 1
          retry unless inc >= 9
          raise 'Puppet master did not start in a timely fashion'
        end
      end

      yield self if block_given?
    ensure
      on host, "if [ -f #{host['puppetpath']}/puppet.conf.bak ]; then " +
                 "cat #{host['puppetpath']}/puppet.conf.bak > " +
                 "#{host['puppetpath']}/puppet.conf; " +
                 "rm -rf #{host['puppetpath']}/puppet.conf.bak; " +
               "fi"
      if host.is_pe?
        on host, '/etc/init.d/pe-httpd restart'
      else
        on host, 'kill $(cat `puppet master --configprint pidfile`)'
      end
    end
  end

  ##############################################################################
  # END_OF Temp Copy of Justins new Puppet Master Methods
  ##############################################################################

  ##############################################################################
  # Object diff functions
  ##############################################################################
  # This is horrible and really doesn't belong here, but I'm not sure where
  # else to put it.  I need a way to do a recursive diff of a hash (which may
  # contain nested objects whose type can be any of Hash, Array, Set, or a
  # scalar).  The hashes may be absolutely gigantic, so if they don't match,
  # I need a way to be able to show a small enough diff so that the user can
  # actually figure out what's going wrong (rather than dumping out the entire
  # gigantic string).  I searched for gems that could handle this and tried
  # 4 or 5 different things, and couldn't find anything that suited the task,
  # so I had to write my own.  This could use improvement, relocation, or
  # replacement with a gem if we ever find a suitable one.
  #
  # UPDATE: chatted with Justin about this and he suggests creating a special
  # puppetlabs-test-utils repo or similar and have that pulled down via
  # bundler, once the acceptance harness is accessible as a gem.  You know,
  # in "The Future".

  # JSON gem doesn't have native support for Set objects, so we have to
  # add this hack.
  class ::Set
    def to_json(arg)
      to_a.to_json(arg)
    end
  end


  def hash_diff(obj1, obj2)
    result =
      (obj1.keys | obj2.keys).inject({}) do |diff, k|
        if obj1[k] != obj2[k]
          objdiff = object_diff(obj1[k], obj2[k])
          if (objdiff)
            diff[k] = objdiff
          end
        end
        diff
      end
    (result == {}) ? nil : result
  end

  def array_diff(arr1, arr2)
    (0..([arr1.length, arr2.length].max)).inject([]) do |diff, i|
      objdiff = object_diff(arr1[i], arr2[i])
      if (objdiff)
        diff << objdiff
      end
      diff
    end
  end

  def set_diff(set1, set2)
    diff1 = set1 - set2
    diff2 = set2 - set1
    unless (diff1.empty? and diff2.empty?)
      [diff1, diff2]
    end
  end

  def object_diff(obj1, obj2)
    if (obj1.class != obj2.class)
      [obj1, obj2]
    else
      case obj1
        when Hash
          hash_diff(obj1, obj2)
        when Array
          array_diff(obj1, obj2)
        when Set
          set_diff(obj1, obj2)
        else
          (obj1 == obj2) ? nil : [obj1, obj2]
      end
    end
  end

  ##############################################################################
  # End Object diff functions
  ##############################################################################

end

# oh dear.
PuppetAcceptance::TestCase.send(:include, PuppetDBExtensions)
