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
    profile("Format payload", [:puppetdb, :payload, :format]) do
      @checksum_payload = Puppet::Util::Puppetdb::CharEncoding.utf8_string({
        :command => command,
        :version => version,
        :certname => certname,
        :payload => payload,
      # We use to_pson still here, to work around the support for shifting
      # binary data from a catalog to PuppetDB. Attempting to use to_json
      # we get to_json conversion errors:
      #
      #   Puppet source sequence is illegal/malformed utf-8
      #   json/ext/GeneratorMethods.java:71:in `to_json'
      #   puppet/util/puppetdb/command.rb:31:in `initialize'
      #
      # This is roughly inline with how Puppet serializes for catalogs as of
      # Puppet 4.1.0. We need a better answer to non-utf8 data end-to-end.
      }.to_pson, "Error encoding a '#{command}' command for host '#{certname}'")
    end
    @command = Puppet::Util::Puppetdb::CharEncoding.coerce_to_utf8(command).gsub(" ", "_")
    @version = version
    @certname = Puppet::Util::Puppetdb::CharEncoding.coerce_to_utf8(certname)
    @producer_timestamp_utc = producer_timestamp_utc
    @payload = Puppet::Util::Puppetdb::CharEncoding.coerce_to_utf8(payload.to_pson)
  end

  attr_reader :command, :version, :certname, :producer_timestamp_utc, :payload, :checksum_payload

  # Submit the command, returning the result hash.
  #
  # @return [Hash <String, String>]
  def submit
    checksum = Digest::SHA1.hexdigest(checksum_payload)

    for_whom = " for #{certname}" if certname
    params = "checksum=#{checksum}&version=#{version}&certname=#{certname}&command=#{command}&producer-timestamp=#{producer_timestamp_utc.iso8601(3)}"
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
