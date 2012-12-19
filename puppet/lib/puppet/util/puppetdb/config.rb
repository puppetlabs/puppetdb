require 'puppet/util/puppetdb/command_names'

module Puppet::Util::Puppetdb
class Config
  include Puppet::Util::Puppetdb::CommandNames

  # Public class methods

  def self.load(config_file = nil)
    defaults = { :server              => "puppetdb",
                 :port                => 8081,
                 :max_queued_commands => 1000,
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
      !([:server, :port, :max_queued_commands].include?(k))
    end

    config_hash[:server] = config_hash[:server].strip
    config_hash[:port] = config_hash[:port].to_i
    config_hash[:max_queued_commands] = config_hash[:max_queued_commands].to_i

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

  def server
    config[:server]
  end

  def port
    config[:port]
  end

  def max_queued_commands
    config[:max_queued_commands]
  end

  # Private instance methods

  private

  attr_reader :config

end
end
