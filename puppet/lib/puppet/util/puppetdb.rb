require 'puppet/util'
require 'puppet/util/puppetdb/char_encoding'
require 'digest'

module Puppet::Util::Puppetdb

  CommandsUrl = "/v1/commands"

  CommandReplaceCatalog = "replace catalog"
  CommandReplaceFacts = "replace facts"
  CommandDeactivateNode = "deactivate node"

  def self.server
    @server, @port = load_puppetdb_config unless @server
    @server
  end

  def self.port
    @server, @port = load_puppetdb_config unless @port
    @port
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
      raise Puppet::Error, "Failed to submit '#{command}' command#{for_whom} to PuppetDB at #{self.class.server}:#{self.class.port}: #{e}"
    end
  end

  def format_command(payload, command, version)
    message = {
      :command => command,
      :version => version,
      :payload => payload.to_pson,
    }.to_pson

    CharEncoding.utf8_string(message)
  end

  private

  def self.load_puppetdb_config
    default_server = "puppetdb"
    default_port = 8081

    config = File.join(Puppet[:confdir], "puppetdb.conf")

    if File.exists?(config)
      Puppet.debug("Configuring PuppetDB terminuses with config file #{config}")
      content = File.read(config)
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
        raise "Setting '#{line}' is illegal outside of section in PuppetDB config #{config}:#{number}" unless section
        result[section][$1] = $2
      when /^\s*[#;]/
        # Skip comments
      when /^\s*$/
        # Skip blank lines
      else
        raise "Unparseable line '#{line}' in PuppetDB config #{config}:#{number}"
      end
    end

    main_section = result['main'] || {}
    server = main_section['server'] || default_server
    port = main_section['port'] || default_port

    [server.strip, port.to_i]
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
