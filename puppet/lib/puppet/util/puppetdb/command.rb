require 'puppet/error'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/http'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/char_encoding'
require 'json'

class Puppet::Util::Puppetdb::Command
  include Puppet::Util::Puppetdb
  include Puppet::Util::Puppetdb::CommandNames

  CommandsUrl = "/pdb/cmd/v1"

  # Recursively coerce all the strings in a payload to UTF-8
  #
  # @param payload Initially the whole Puppet object converted
  #   to_data_hash. This is called recusively if the command has neseted
  #   structures.
  # @param error_context_str a string prefix for log messages
  def coerce_payload(payload, error_context_str)
    c = ""
    had_lossy_string = false
    case payload
    when Hash
      c = payload.each_with_object({}) do |(k, v), memo|
        new_k, l = coerce_payload(k, error_context_str)
        had_lossy_string ||= l

        new_v, l = coerce_payload(v, error_context_str)
        had_lossy_string ||= l
        memo[new_k] = new_v
      end
    when Array
      c = payload.map do |v|
        new_v, l = coerce_payload(v, error_context_str)

        had_lossy_string ||= l
        new_v
      end
    when String
      coerced_str = Puppet::Util::Puppetdb::CharEncoding.coerce_to_utf8(payload)

      if coerced_str != payload
        had_lossy_string = true

        if Puppet.settings[:log_level] == "debug"
          Puppet.debug error_context_str + "\n" + Puppet::Util::Puppetdb::CharEncoding.error_char_context(coerced_str)
        end
      end

      c = coerced_str
    else
      c = payload
    end

    [c, had_lossy_string]
  end

  # Public instance methods

  # Initialize a Command object, for later submission.
  #
  # @param command String the name of the command; should be one of the
  #   constants defined in `Puppet::Util::Puppetdb::CommandNames`
  # @param version Integer the command version number
  # @param certname The certname that this command operates on (is not
  #   included in the actual submission)
  # @param payload Object the payload of the command.  This object should be a
  #   primitive (numeric type, string, array, or hash) that is natively supported
  #   by JSON serialization / deserialization libraries.
  def initialize(command, version, certname, producer_timestamp_utc, payload)
    coerced_payload = nil
    profile("Format payload", [:puppetdb, :payload, :format]) do
      error_context_str = "Lossy encoding of a '#{command}' command for host '#{certname}'"

      coerced_payload, had_lossy_string = coerce_payload(payload, error_context_str)

      if had_lossy_string
        Puppet.warning "#{error_context_str} ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB, see debug logging for more info"
      end

      # coerce to json while we are still inside the profiling block
      coerced_payload = coerced_payload.to_json
    end
    @command = Puppet::Util::Puppetdb::CharEncoding.coerce_to_utf8(command).gsub(" ", "_")
    @version = version
    @certname = Puppet::Util::Puppetdb::CharEncoding.coerce_to_utf8(certname)
    @producer_timestamp_utc = producer_timestamp_utc
    @payload = coerced_payload
  end

  attr_reader :command, :version, :certname, :producer_timestamp_utc, :payload

  # Submit the command, returning the result hash.
  #
  # @return [Hash <String, String>]
  def submit
    for_whom = " for #{certname}" if certname
    params = "version=#{version}&certname=#{certname}&command=#{command}&producer-timestamp=#{producer_timestamp_utc.iso8601(3)}"
    begin
      response = profile("Submit command HTTP post", [:puppetdb, :command, :submit]) do
        Http.action("#{CommandsUrl}?#{params}", :command) do |http_instance, path, ssl_context|
          req_headers = headers
          # custom header used in PDB to reject large compressed commands and update the size metric
          req_headers["X-Uncompressed-Length"] = payload.bytesize.to_s
          http_instance.post(path, payload, **{headers: req_headers,
                                               options: {compress: :gzip,
                                                         metric_id: [:puppetdb, :command, command],
                                                         ssl_context: ssl_context}})
        end
      end

      Puppet::Util::Puppetdb.log_x_deprecation_header(response)

      if response.success?
        result = JSON.parse(response.body)
        Puppet.info "'#{command}' command#{for_whom} submitted to PuppetDB with UUID #{result['uuid']}"
        result
      else
        # Newline characters cause an HTTP error, so strip them
        error = "[#{response.code} #{response.reason}] #{response.body.gsub(/[\r\n]/, '')}"
        if config.soft_write_failure
          Puppet.err "'#{command}'command#{for_whom} failed during submission to PuppetDB: #{error}"
        else
          raise Puppet::Error, error
        end
      end
    rescue => e
      if config.soft_write_failure
        Puppet.err e.message
      else
        Puppet.log_exception(e)
        raise Puppet::Util::Puppetdb::CommandSubmissionError.new(e.message, {:command => command, :for_whom => for_whom})
      end
    end
  end

  # @!group Private instance methods

  # @api private
  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/json; charset=utf-8",
    }
  end

  # @api private
  def config
    # Would prefer to pass this to the constructor or acquire it some other
    # way besides this pseudo-global reference.
    Puppet::Util::Puppetdb.config
  end
end
