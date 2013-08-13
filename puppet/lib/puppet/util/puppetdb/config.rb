require 'puppet/util/puppetdb/command_names'

module Puppet::Util::Puppetdb
class Config
  include Puppet::Util::Puppetdb::CommandNames

  BlacklistedEvent = Struct.new(:resource_type, :resource_title, :status, :property)

  # Initialize our blacklist of events to filter out of reports.  This is needed
  # because older versions of puppet always generate a swath of (meaningless)
  # 'skipped' Schedule events on every agent run.  As of puppet 3.3, these
  # events should no longer be generated, but this is here for backward compat.
  BlacklistedEvents =
      [BlacklistedEvent.new("Schedule", "never", "skipped", nil),
       BlacklistedEvent.new("Schedule", "puppet", "skipped", nil),
       BlacklistedEvent.new("Schedule", "hourly", "skipped", nil),
       BlacklistedEvent.new("Schedule", "daily", "skipped", nil),
       BlacklistedEvent.new("Schedule", "weekly", "skipped", nil),
       BlacklistedEvent.new("Schedule", "monthly", "skipped", nil)]

  # Public class methods

  def self.load(config_file = nil)
    defaults = { :server                      => "puppetdb",
                 :port                        => 8081,
                 :ignore_blacklisted_events   => true,
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
      !([:server, :port, :ignore_blacklisted_events].include?(k))
    end

    config_hash[:server] = config_hash[:server].strip
    config_hash[:port] = config_hash[:port].to_i
    config_hash[:ignore_blacklisted_events] =
        Puppet::Util::Puppetdb.to_bool(config_hash[:ignore_blacklisted_events])

    self.new(config_hash)
  rescue => detail
    puts detail.backtrace if Puppet[:trace]
    Puppet.warning "Could not configure PuppetDB terminuses: #{detail}"
    raise
  end

  # Public instance methods

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
    blacklisted_events.fetch(event["resource-type"], {}).
      fetch(event["resource-title"], {}).
      fetch(event["status"], {}).
      fetch(event["property"], false)
  end

  # Private instance methods
  private

  attr_reader :config
  attr_reader :blacklisted_events

  def initialize_blacklisted_events(events = BlacklistedEvents)
    @blacklisted_events = events.reduce({}) do |m, e|
      m[e.resource_type] ||= {}
      m[e.resource_type][e.resource_title] ||= {}
      m[e.resource_type][e.resource_title][e.status] ||= {}
      m[e.resource_type][e.resource_title][e.status][e.property] = true
      m
    end
  end

end
end
