require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/blacklist'

module Puppet::Util::Puppetdb
class Config
  include Puppet::Util::Puppetdb::CommandNames

  # Public class methods

  def self.load(config_file = nil)
    defaults = {
      :server                    => "puppetdb",
      :port                      => 8081,
      :soft_write_failure        => false,
      :ignore_blacklisted_events => true,
    }

    config_file ||= File.join(Puppet[:confdir], "puppetdb.conf")

    if File.exists?(config_file)
      Puppet.debug("Configuring PuppetDB terminuses with config file #{config_file}")
      content = File.read(config_file)
    else
      Puppet.debug("No #{config_file} file found; falling back to default server and port #{defaults[:server]}:#{defaults[:port]}")
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


    main_section = result['main'] || {}
    # symbolize the keys
    main_section = main_section.inject({}) {|h, (k,v)| h[k.to_sym] = v ; h}
    # merge with defaults but filter out anything except the legal settings
    config_hash = defaults.merge(main_section).reject do |k, v|
      !([:server, :port, :ignore_blacklisted_events, :soft_write_failure].include?(k))
    end

    config_hash[:server] = config_hash[:server].strip
    config_hash[:port] = config_hash[:port].to_i
    config_hash[:ignore_blacklisted_events] =
      Puppet::Util::Puppetdb.to_bool(config_hash[:ignore_blacklisted_events])
    config_hash[:soft_write_failure] =
      Puppet::Util::Puppetdb.to_bool(config_hash[:soft_write_failure])

    self.new(config_hash)
  rescue => detail
    puts detail.backtrace if Puppet[:trace]
    Puppet.warning "Could not configure PuppetDB terminuses: #{detail}"
    raise
  end

  # @!group Public instance methods

  def initialize(config_hash = {})
    @config = config_hash
    initialize_blacklisted_events()
  end

  def server
    config[:server]
  end

  def port
    config[:port]
  end

  def ignore_blacklisted_events?
    config[:ignore_blacklisted_events]
  end

  def is_event_blacklisted?(event)
   @blacklist.is_event_blacklisted? event
  end

  def soft_write_failure
    config[:soft_write_failure]
  end

  # @!group Private instance methods

  # @!attribute [r] count
  #   @api private
  attr_reader :config

  Blacklist = Puppet::Util::Puppetdb::Blacklist

  # @api private
  def initialize_blacklisted_events(events = Blacklist::BlacklistedEvents)
    @blacklist = Blacklist.new(events)
  end
end
end
