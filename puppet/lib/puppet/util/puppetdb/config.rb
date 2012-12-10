require 'puppet/util/puppetdb/command_names'

module Puppet::Util::Puppetdb
class Config
  include Puppet::Util::Puppetdb::CommandNames

  # a map for looking up config file section names that correspond to our
  # individual commands
  CommandConfigSectionNames = {
      CommandReplaceCatalog => :catalogs,
      CommandReplaceFacts   => :facts,
      CommandStoreReport    => :reports,
  }

  # Public class methods

  def self.load(config_file = nil)
    default_server = "puppetdb"
    default_port = 8081
    default_spool_settings = {
        CommandReplaceCatalog => false,
        CommandReplaceFacts   => false,
        CommandStoreReport    => true,
    }

    config_file ||= File.join(Puppet[:confdir], "puppetdb.conf")

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
      command_section = result[CommandConfigSectionNames[c].to_s]
      config_hash[c][:spool] = (command_section && command_section.has_key?('spool')) ?
          (command_section['spool'] == "true") :
          default_spool_settings[c]
    end

    self.new(config_hash)
  rescue => detail
    puts detail.backtrace if Puppet[:trace]
    Puppet.warning "Could not configure PuppetDB terminuses: #{detail}"
    raise
  end

  # Public instance methods

  def initialize(config_hash = {})
    @config = config_hash
  end

  def command_spooled?(command_name)
    config.has_key?(command_name) ? config[command_name][:spool] : false
  end

  def [](property)
    # TODO: ultimately it might be nice to define a more rigid API, rather than
    #  allowing access to the internals of the config hash.
    config[property]
  end

  # Private instance methods

  private

  attr_reader :config

end
end
