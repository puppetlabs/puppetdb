require 'puppet/error'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/char_encoding'

class Puppet::Util::Puppetdb::Command
  include Puppet::Util::Puppetdb::CommandNames

  Url                = "/v2/commands"

  # Public instance methods

  # Constructor;
  #
  # @param command String the name of the command; should be one of the
  #   constants defined in `Puppet::Util::Puppetdb::CommandNames`
  # @param version Integer the command version number
  # @param payload Object the payload of the command.  This object should be a
  #   primitive (numeric type, string, array, or hash) that is natively supported
  #   by JSON serialization / deserialization libraries.
  def initialize(command, version, certname, payload)
    @command = command
    @version = version
    @certname = certname
    @payload = self.class.format_payload(command, version, payload)
  end

  attr_reader :command, :version, :certname, :payload

  def submit
    checksum = Digest::SHA1.hexdigest(payload)
    escaped_payload = CGI.escape(payload)
    for_whom = " for #{certname}" if certname

    begin
      http = Puppet::Network::HttpPool.http_instance(config.server, config.port)
      response = http.post(Url, "checksum=#{checksum}&payload=#{escaped_payload}", headers)

      Puppet::Util::Puppetdb.log_x_deprecation_header(response)

      if response.is_a? Net::HTTPSuccess
        result = PSON.parse(response.body)
        Puppet.info "'#{command}' command#{for_whom} submitted to PuppetDB with UUID #{result['uuid']}"
        result
      else
        # Newline characters cause an HTTP error, so strip them
        raise Puppet::Error, "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
      end
    rescue => e
      # TODO: Use new exception handling methods from Puppet 3.0 here as soon as
      #  we are able to do so (can't call them yet w/o breaking backwards
      #  compatibility.)  We should either be using a nested exception or calling
      #  Puppet::Util::Logging#log_exception or #log_and_raise here; w/o them
      #  we lose context as to where the original exception occurred.
      puts e, e.backtrace if Puppet[:trace]
      raise Puppet::Error, "Failed to submit '#{command}' command#{for_whom} to PuppetDB at #{config.server}:#{config.port}: #{e}"
    end
  end


  private

  ## Private class methods

  def self.format_payload(command, version, payload)
    message = {
        :command => command,
        :version => version,
        :payload => payload,
    }.to_pson

    Puppet::Util::Puppetdb::CharEncoding.utf8_string(message)
  end


  ## Private instance methods

  def headers
    {
        "Accept" => "application/json",
        "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end

  def config
    # Would prefer to pass this to the constructor or acquire it some other
    # way besides this pseudo-global reference.
    Puppet::Util::Puppetdb.config
  end

end
