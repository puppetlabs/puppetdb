require 'uri'
require 'puppet/network/http_pool'
require 'net/http'
require 'timeout'
require 'pp'

module Puppet::Util::Puppetdb
  class Http

    SERVER_URL_FAIL_MSG = "Failing over to the next PuppetDB url in the 'server_urls' list"

    # Concat two url snippets, taking into account a trailing/leading slash to
    # ensure a correct url is constructed
    #
    # @param snippet1 [String] first URL snippet
    # @param snippet2 [String] second URL snippet
    # @return [String] returns http response
    # @api private
    def self.concat_url_snippets(snippet1, snippet2)
      if snippet1.end_with?('/') and snippet2.start_with?('/')
        snippet1 + snippet2[1..-1]
      elsif !snippet1.end_with?('/') and !snippet2.start_with?('/')
        snippet1 + '/' + snippet2
      else
        snippet1 + snippet2
      end
    end

    # Setup an http connection, provide a block that will do something with that http
    # connection. The block should be a two argument block, accepting the connection (which
    # you can call get or post on for example) and the properly constructed path, which
    # will be the concatenated version of any url_prefix and the path passed in.
    #
    # @param path_suffix [String] path for the get/post of the http action
    # @param http_callback [Proc] proc containing the code calling the action on the http connection
    # @return [Response] returns http response
    def self.action(path_suffix, &http_callback)

      response = nil
      config = Puppet::Util::Puppetdb.config
      server_url_config = config.server_url_config?

      for url in Puppet::Util::Puppetdb.config.server_urls
        begin
          route = concat_url_snippets(url.request_uri, path_suffix)
          http = Puppet::Network::HttpPool.http_instance(url.host, url.port)
          request_timeout = config.server_url_timeout

          response = timeout(request_timeout) do
            http_callback.call(http, route)
          end

          if response.is_a? Net::HTTPServerError
            Puppet.warning("Error connecting to #{url.host} on #{url.port} at route #{route}, error message received was '#{response.message}'. #{SERVER_URL_FAIL_MSG if server_url_config}")
            response = nil
          elsif response.is_a? Net::HTTPNotFound
            if response.body && response.body.chars.first == "{"
              # If it appears to be json, we've probably gotten an authentic 'not found' message.
              Puppet.debug("HTTP 404 (probably normal) when connecting to #{url.host} on #{url.port} at route #{route}, error message received was '#{response.message}'. #{SERVER_URL_FAIL_MSG if server_url_config}")
              response = :notfound
            else
              # But we can also get 404s when conneting to a puppetdb that's still starting or due to misconfiguration.
              Puppet.warning("Error connecting to #{url.host} on #{url.port} at route #{route}, error message received was '#{response.message}'. #{SERVER_URL_FAIL_MSG if server_url_config}")
              response = nil
            end
          else
            break
          end
        rescue Timeout::Error => e
          Puppet.warning("Request to #{url.host} on #{url.port} at route #{route} timed out after #{request_timeout} seconds. #{SERVER_URL_FAIL_MSG if server_url_config}")

        rescue SocketError, OpenSSL::SSL::SSLError, SystemCallError, Net::ProtocolError, IOError, Net::HTTPNotFound => e
          Puppet.warning("Error connecting to #{url.host} on #{url.port} at route #{route}, error message received was '#{e.message}'. #{SERVER_URL_FAIL_MSG if server_url_config}")

        rescue Puppet::Util::Puppetdb::InventorySearchError => e
          Puppet.warning("Could not perform inventory search from PuppetDB at #{url.host}:#{url.port}: '#{e.message}' #{SERVER_URL_FAIL_MSG if server_url_config}")

        rescue Puppet::Util::Puppetdb::CommandSubmissionError => e
          error = "Failed to submit '#{e.context[:command]}' command for '#{e.context[:for_whom]}' to PuppetDB at #{url.host}:#{url.port}: '#{e.message}'."
          if config.soft_write_failure
            Puppet.err error
          else
            Puppet.warning(error + " #{SERVER_URL_FAIL_MSG if server_url_config}")
          end
        rescue Puppet::Util::Puppetdb::SoftWriteFailError => e
          Puppet.warning("Failed to submit '#{e.context[:command]}' command for '#{e.context[:for_whom]}' to PuppetDB at #{url.host}:#{url.port}: '#{e.message}' #{SERVER_URL_FAIL_MSG if server_url_config}")
        rescue Puppet::Error => e
          if e.message =~ /did not match server certificate; expected one of/
            Puppet.warning("Error connecting to #{url.host} on #{url.port} at route #{route}, error message received was '#{e.message}'. #{SERVER_URL_FAIL_MSG if server_url_config}")
          else
            raise
          end
        end
      end

      if response.nil? or response == :notfound
        if server_url_config
          server_url_strings = Puppet::Util::Puppetdb.config.server_urls.map {|url| url.to_s}.join(', ')
          if response == :notfound
            raise NotFoundError, "Failed to find '#{path_suffix}' on any of the following 'server_urls': #{server_url_strings}"
          else
            raise Puppet::Error, "Failed to execute '#{path_suffix}' on any of the following 'server_urls': #{server_url_strings}"
          end
        else
          uri = Puppet::Util::Puppetdb.config.server_urls.first
          if response == :notfound
            raise NotFoundError, "Failed to find '#{path_suffix}' on server: '#{uri.host}' and port: '#{uri.port}'"
          else
            raise Puppet::Error, "Failed to execute '#{path_suffix}' on server: '#{uri.host}' and port: '#{uri.port}'"
          end
        end
      end

      response

    end
  end

  class NotFoundError < Puppet::Error
  end
end
