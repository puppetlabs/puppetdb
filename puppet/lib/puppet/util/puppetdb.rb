require 'puppet/util'
require 'puppet/util/puppetdb/char_encoding'
require 'digest'
require 'time'
require 'fileutils'

# TODO: This module is intended to be mixed-in by subclasses of
# `Puppet::Indirector::REST`.  This is unfortunate because the code is useful
# in other cases as well, but it's hard to use the "right" methods in Puppet
# for making HTTPS requests using the Puppet SSL config / certs outside of the
# indirector.  This has been fixed as of Puppet 3.0, (commit fa32691db3a7c2326118d528144fd1df824c0bb3), and this module
# should probably be refactored once we don't need to support
# versions prior to that.  See additional comments
# below, and see also `Puppet::Util::Puppetdb::ReportHelper` for an example of
# how to work around this limitation for the time being.
module Puppet::Util::Puppetdb

  CommandsUrl = "/v2/commands"

  CommandReplaceCatalog   = "replace catalog"
  CommandReplaceFacts     = "replace facts"
  CommandDeactivateNode   = "deactivate node"
  CommandStoreReport      = "store report"

  # a map for looking up config file section names that correspond to our
  # individual commands
  CommandsConfigSectionNames = {
      CommandReplaceCatalog => :catalogs,
      CommandReplaceFacts   => :facts,
      CommandStoreReport    => :reports,
  }

  def self.server
    config[:server]
  end

  def self.port
    config[:port]
  end

  def self.config
    @config ||= load_puppetdb_config
    @config
  end

  module ClassMethods
    def server
      Puppet::Util::Puppetdb.server
    end

    def port
      Puppet::Util::Puppetdb.port
    end
  end

  def self.included(child)
    child.extend ClassMethods
  end

  def submit_command(request, command_payload, command, version)
    message = format_command(command_payload, command, version)

    checksum = Digest::SHA1.hexdigest(message)

    payload = CGI.escape(message)

    for_whom = " for #{request.key}" if request.key

    begin
      # TODO: This line introduces a requirement that any class that mixes in this
      # module must either be a subclass of `Puppet::Indirector::REST`, or
      # implement its own compatible `#http_post` method, which, unfortunately,
      # is not likely to have the same error handling functionality as the
      # one in the REST class.  This was addressed in the following Puppet ticket:
      #  http://projects.puppetlabs.com/issues/15975
      #
      # and has been fixed in Puppet 3.0, so we can clean this up as soon we no longer need to maintain
      # backward-compatibity with older versions of Puppet.
      response = http_post(request, CommandsUrl, "checksum=#{checksum}&payload=#{payload}", headers)

      log_x_deprecation_header(response)

      if response.is_a? Net::HTTPSuccess
        result = PSON.parse(response.body)
        Puppet.info "'#{command}' command#{for_whom} submitted to PuppetDB with UUID #{result['uuid']}"
        result
      else
        # Newline characters cause an HTTP error, so strip them
        raise "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
      end
    rescue => e
      # TODO: Use new exception handling methods from Puppet 3.0 here as soon as
      #  we are able to do so (can't call them yet w/o breaking backwards
      #  compatibility.)  We should either be using a nested exception or calling
      #  Puppet::Util::Logging#log_exception or #log_and_raise here; w/o them
      #  we lose context as to where the original exception occurred.
      raise Puppet::Error, "Failed to submit '#{command}' command#{for_whom} to PuppetDB at #{self.class.server}:#{self.class.port}: #{e}"
    end
  end

  ## Given an instance of ruby's Time class, this method converts it to a String
  ## that conforms to PuppetDB's wire format for representing a date/time.
  def self.to_wire_time(time)
    # The current implementation simply calls iso8601, but having this method
    # allows us to change that in the future if needed w/o being forced to
    # update all of the date objects elsewhere in the code.
    time.iso8601
  end


  private

  def format_command(payload, command, version)
    message = {
      :command => command,
      :version => version,
      :payload => payload,
    }.to_pson

    CharEncoding.utf8_string(message)
  end

  private

  def self.load_puppetdb_config
    default_server = "puppetdb"
    default_port = 8081
    default_spool_settings = {
        CommandReplaceCatalog => false,
        CommandReplaceFacts   => false,
        CommandStoreReport    => true,
    }

    config_file = File.join(Puppet[:confdir], "puppetdb.conf")

    if File.exists?(config_file)
      Puppet.debug("Configuring PuppetDB terminuses with config file #{config_file}")
      content = File.read(config_file)
    else
      Puppet.debug("No puppetdb.conf file found; falling back to default #{default_server}:#{default_port}")
      content = ''
    end

    result = {}
    section = nil
    content.lines.each_with_index do |line,number|
      # Gotta track the line numbers properly
      number += 1
      case line
      when /^\[(\w+)\s*\]$/
        section = $1
        result[section] ||= {}
      when /^\s*(\w+)\s*=\s*(\S+)\s*$/
        raise "Setting '#{line}' is illegal outside of section in PuppetDB config #{config_file}:#{number}" unless section
        result[section][$1] = $2
      when /^\s*[#;]/
        # Skip comments
      when /^\s*$/
        # Skip blank lines
      else
        raise "Unparseable line '#{line}' in PuppetDB config #{config_file}:#{number}"
      end
    end

    config_hash = {}

    main_section = result['main'] || {}
    config_hash[:server] = (main_section['server'] || default_server).strip
    config_hash[:port] = (main_section['port'] || default_port).to_i

    [CommandReplaceCatalog, CommandReplaceFacts, CommandStoreReport].each do |c|
      config_hash[c] = {}
      command_section = result[CommandsConfigSectionNames[c].to_s]
      config_hash[c][:spool] = (command_section && command_section.has_key?('spool')) ?
                                  (command_section['spool'] == "true") :
                                  default_spool_settings[c]
    end

    config_hash
  rescue => detail
    puts detail.backtrace if Puppet[:trace]
    Puppet.warning "Could not configure PuppetDB terminuses: #{detail}"
    raise
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end

  def log_x_deprecation_header(response)
    if warning = response['x-deprecation']
      Puppet.deprecation_warning "Deprecation from PuppetDB: #{warning}"
    end
  end
end
