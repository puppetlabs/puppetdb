require 'puppet/util/puppetdb/command_names'
require 'uri'

module Puppet::Util::Puppetdb
  class Config
    include Puppet::Util::Puppetdb::CommandNames

    # Public class methods

    def self.load(config_file = nil)
      defaults = {
        :server_urls                 => "https://puppetdb:8081",
        :soft_write_failure          => false,
        :server_url_timeout          => 30,
        :include_unchanged_resources => false,
      }

      config_file ||= File.join(Puppet[:confdir], "puppetdb.conf")

      if File.exists?(config_file)
        Puppet.debug("Configuring PuppetDB terminuses with config file #{config_file}")
        content = File.read(config_file)
      else
        Puppet.debug("No #{config_file} file found; falling back to default server_urls #{defaults[:server_urls]}")
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

        when /^\s*(\w+)\s*=\s*(\S+|[\S+\s*\,\s*\S]+)\s*$/
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
        !([:server_urls,
           :ignore_blacklisted_events,
           :include_unchanged_resources,
           :soft_write_failure,
           :server_url_timeout].include?(k))
      end

      config_hash[:server_urls] = convert_and_validate_urls(config_hash[:server_urls].split(",").map {|s| s.strip})
      config_hash[:server_url_timeout] = config_hash[:server_url_timeout].to_i
      config_hash[:include_unchanged_resources] = Puppet::Util::Puppetdb.to_bool(config_hash[:include_unchanged_resources])
      config_hash[:soft_write_failure] = Puppet::Util::Puppetdb.to_bool(config_hash[:soft_write_failure])

      self.new(config_hash)
    rescue => detail
      Puppet.warning "Could not configure PuppetDB terminuses: #{detail}"
      Puppet.warning detail.backtrace if Puppet[:trace]
      raise
    end

    # @!group Public instance methods

    def initialize(config_hash = {})
      @config = config_hash
    end

    def server_urls
      config[:server_urls]
    end

    def server_url_timeout
      config[:server_url_timeout]
    end

    def include_unchanged_resources?
      config[:include_unchanged_resources]
    end

    def soft_write_failure
      config[:soft_write_failure]
    end

    # @!group Private instance methods

    # @!attribute [r] count
    #   @api private
    attr_reader :config

    # @api private
    def self.convert_and_validate_urls(uri_strings)
      uri_strings.map do |uri_string|

        begin
          uri = URI(uri_string.strip)
        rescue URI::InvalidURIError => e
          raise URI::InvalidURIError.new, "Error parsing URL '#{uri_string}' in PuppetDB 'server_urls', error message was '#{e.message}'"
        end

        if uri.scheme != 'https'
          raise "PuppetDB 'server_urls' must be https, found '#{uri_string}'"
        end

        if uri.path != '' && uri.path != '/'
          raise "PuppetDB 'server_urls' cannot contain URL paths, found '#{uri_string}'"
        end
        uri.path = ''
        uri
      end
    end
  end
end
