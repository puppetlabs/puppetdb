require 'uri'
require 'puppet/network/http_pool'
require 'net/http'
require 'timeout'

module Puppet::Util::Puppetdb
  class Http
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

      for url in Puppet::Util::Puppetdb.config.server_urls
        begin
          route = concat_url_snippets(url.request_uri, path_suffix)
          http = Puppet::Network::HttpPool.http_instance(url.host, url.port)
          request_timeout = Puppet::Util::Puppetdb.config.server_url_timeout

          response = timeout(request_timeout) do
            http_callback.call(http, route)
          end

          if response.is_a? Net::HTTPServerError
            Puppet.warning("Attempted to connect #{url.host} on #{url.port} at route #{route} and failed with code #{response.code} and error message #{response.message}. Failing to next PuppetDB url in the 'server_urls' list.")
            response = nil
          else
            break
          end
        rescue Timeout::Error => e
          Puppet.warning("Request to #{url.host} on #{url.port} at route #{route} timed out after #{request_timeout}. Failing over to the next PuppetDB urls in the 'server_urls' list")

        rescue SystemCallError, Net::ProtocolError, IOError => e
          Puppet.warning("Error connecting to #{url.host} on #{url.port} at route #{route}, error message received was '#{e.message}'. Failing over to the next PuppetDB urls in the 'server_urls' list")
        end
      end

      if response.nil?
        server_url_strings = Puppet::Util::Puppetdb.config.server_urls.map {|url| url.to_s}.join(',')
        raise Puppet::Error, "Failed to execute '#{path_suffix}' on any of the following 'server_urls' #{server_url_strings}"
      end

      response

    end
  end
end
