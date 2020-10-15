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
        :min_successful_submissions  => 1,
        :submit_only_server_urls     => "",
        :command_broadcast           => false,
        :sticky_read_failover        => false,
        :verify_client_certificate   => true
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

        when /^\s*(server|port)\s*=.*$/
          Puppet.warning("Setting '#{line.chomp}' is retired: use 'server_urls' instead. Defaulting to 'server_urls=https://puppetdb:8081'.")
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
           :server_url_timeout,
           :min_successful_submissions,
           :submit_only_server_urls,
           :command_broadcast,
           :sticky_read_failover,
           :verify_client_certificate].include?(k))
      end

      parsed_urls = config_hash[:server_urls].split(",").map {|s| s.strip}
      config_hash[:server_urls] = convert_and_validate_urls(parsed_urls)

      config_hash[:server_url_timeout] = config_hash[:server_url_timeout].to_i
      config_hash[:include_unchanged_resources] = Puppet::Util::Puppetdb.to_bool(config_hash[:include_unchanged_resources])
      config_hash[:soft_write_failure] = Puppet::Util::Puppetdb.to_bool(config_hash[:soft_write_failure])

      config_hash[:submit_only_server_urls] = convert_and_validate_urls(config_hash[:submit_only_server_urls].split(",").map {|s| s.strip})
      config_hash[:min_successful_submissions] = config_hash[:min_successful_submissions].to_i
      config_hash[:command_broadcast] = Puppet::Util::Puppetdb.to_bool(config_hash[:command_broadcast])
      config_hash[:sticky_read_failover] = Puppet::Util::Puppetdb.to_bool(config_hash[:sticky_read_failover])
      config_hash[:verify_client_certificate] = Puppet::Util::Puppetdb.to_bool(config_hash[:verify_client_certificate])

      if config_hash[:soft_write_failure] and config_hash[:min_successful_submissions] > 1
        raise "soft_write_failure cannot be enabled when min_successful_submissions is greater than 1"
      end

      overlapping_server_urls = config_hash[:server_urls] & config_hash[:submit_only_server_urls]
      if overlapping_server_urls.length > 0
        overlapping_server_urls_strs = overlapping_server_urls.map { |u| u.to_s }
        raise "Server URLs must be in either server_urls or submit_only_server_urls, not both. "\
          "(#{overlapping_server_urls_strs.to_s} are in both)"
      end

      if config_hash[:min_successful_submissions] > 1 and not config_hash[:command_broadcast]
        raise "command_broadcast must be set to true to use min_successful_submissions"
      end

      if config_hash[:min_successful_submissions] > config_hash[:server_urls].length
        raise "min_successful_submissions (#{config_hash[:min_successful_submissions]}) must be less than "\
          "or equal to the number of server_urls (#{config_hash[:server_urls].length})"
      end

      self.new(config_hash)
    rescue => detail
      Puppet.log_exception detail, "Could not configure PuppetDB terminuses: #{detail.message}", {level: :warning}
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

    def min_successful_submissions
      config[:min_successful_submissions]
    end

    def submit_only_server_urls
      config[:submit_only_server_urls]
    end

    def command_broadcast
      config[:command_broadcast]
    end

    def sticky_read_failover
      config[:sticky_read_failover]
    end

    def verify_client_certificate
      config[:verify_client_certificate]
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
