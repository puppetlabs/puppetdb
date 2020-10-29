require 'uri'
require 'puppet/network/http_pool'
require 'net/http'
require 'timeout'
require 'pp'
require 'thread'
require 'puppet/util/puppetdb/atom'

module Puppet::Util::Puppetdb
  class Http
    SERVER_URL_FAIL_MSG = "Failing over to the next PuppetDB server_url in the 'server_urls' list"

    @@last_good_query_server_url_index = Atom.new(0)

    # Concat two server_url snippets, taking into account a trailing/leading slash to
    # ensure a correct server_url is constructed
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

    # Run the given block (cb) in a begin/rescue, catching common network
    # exceptions and logging useful information about them. If an expected
    # exception was caught, it's returned. An unexpected exception will be
    # re-thrown. Returns nil on success.
    def self.with_http_error_logging(server_url, route, &cb)
      config = Puppet::Util::Puppetdb.config

      begin
        cb.call()
      rescue Timeout::Error => e
        Puppet.warning("Request to #{server_url.host} on #{server_url.port} at route #{route} timed out " \
          "after #{config.server_url_timeout} seconds. #{SERVER_URL_FAIL_MSG}")
        return e

      rescue Puppet::HTTP::HTTPError, Puppet::SSL::SSLError => e
        Puppet.warning("Error connecting to #{server_url.host} on #{server_url.port} at route #{route}, " \
          "error message received was '#{e.message}'. #{SERVER_URL_FAIL_MSG}")
        return e

      rescue Puppet::Util::Puppetdb::InventorySearchError => e
        Puppet.warning("Could not perform inventory search from PuppetDB at #{server_url.host}:#{server_url.port}: " \
          "'#{e.message}' #{SERVER_URL_FAIL_MSG}")
        return e

      rescue Puppet::Util::Puppetdb::CommandSubmissionError => e
        error = "Failed to submit '#{e.context[:command]}' command for '#{e.context[:for_whom]}' to PuppetDB " \
          "at #{server_url.host}:#{server_url.port}: '#{e.message}'."
        if config.soft_write_failure
          Puppet.err error
        else
          Puppet.warning(error + " #{SERVER_URL_FAIL_MSG}")
        end
        return e

      rescue Puppet::Util::Puppetdb::SoftWriteFailError => e
        Puppet.warning("Failed to submit '#{e.context[:command]}' command for '#{e.context[:for_whom]}' to PuppetDB " \
          "at #{server_url.host}:#{server_url.port}: '#{e.message}' #{SERVER_URL_FAIL_MSG}")
        return e

      rescue Puppet::Error => e
        if e.message =~ /did not match server certificate; expected one of/
          Puppet.warning("Error connecting to #{server_url.host} on #{server_url.port} at route #{route}, " \
            "error message received was '#{e.message}'. #{SERVER_URL_FAIL_MSG}")
          return e
        else
          raise
        end
      end

      nil
    end

    # Check an http reponse from puppetdb; log a useful message if it looks like
    # something went wrong. Return a symbol indicating the problem
    # (:server_error, :notfound, or :other_404), or nil if there wasn't one.
    def self.check_http_response(response, server_url, route)
      if response.code >= 500
        Puppet.warning("Error connecting to #{server_url.host} on #{server_url.port} at route #{route}, " \
          "error message received was '#{response.reason}'. #{SERVER_URL_FAIL_MSG}")
        :server_error
      elsif response.code == 404
        if response.body && response.body.chars.first == "{"
          # If it appears to be json, we've probably gotten an authentic 'not found' message.
          Puppet.debug("HTTP 404 (probably normal) when connecting to #{server_url.host} on #{server_url.port} " \
            "at route #{route}, error message received was '#{response.reason}'. #{SERVER_URL_FAIL_MSG}")
          :notfound
        else
          # But we can also get 404s when conneting to a puppetdb that's still starting or due to misconfiguration.
          Puppet.warning("Error connecting to #{server_url.host} on #{server_url.port} at route #{route}, " \
            "error message received was '#{response.reason}'. #{SERVER_URL_FAIL_MSG}")
          :other_404
        end
      else
        nil
      end
    end

    def self.raise_request_error(response, response_error, path_suffix)
      server_url_strings = Puppet::Util::Puppetdb.config.server_urls.map {|server_url| server_url.to_s}.join(', ')
      if response_error == :notfound
        raise NotFoundError, "Failed to find '#{path_suffix}' on any of the following 'server_urls': #{server_url_strings}"
      else
        min_successful_submissions = Puppet::Util::Puppetdb.config.min_successful_submissions
        raise Puppet::Error, "Failed to execute '#{path_suffix}' on at least #{min_successful_submissions} of the following 'server_urls': #{server_url_strings}"
      end
    end

    def self.failover_action(path_suffix, server_urls, sticky, ssl_context = nil, http_callback)
      response = nil
      response_error = nil
      config = Puppet::Util::Puppetdb.config
      last_good_index = 0

      if sticky
        last_good_index = @@last_good_query_server_url_index.deref()
      end

      server_count = server_urls.length
      server_try_order = (0...server_count).map { |i| (i + last_good_index) % server_count }

      for server_url_index in server_try_order
        server_url = server_urls[server_url_index]
        route = concat_url_snippets(server_url.to_s, path_suffix)

        request_exception = with_http_error_logging(server_url, route) {
          http = Puppet.runtime[:http]

          response = Timeout.timeout(config.server_url_timeout) do
            begin
              http_callback.call(http, URI(route), ssl_context)
            rescue Puppet::HTTP::ResponseError => e
              e.response
            end
          end
        }

        if request_exception.nil?
          response_error = check_http_response(response, server_url, route)
          if response_error.nil?
            if server_url_index != server_try_order.first()
              @@last_good_query_server_url_index.reset(server_url_index)
            end
            break
          end
        end
      end

      if response.nil? or not(response_error.nil?)
        raise_request_error(response, response_error, path_suffix)
      end

      response
    end

    def self.broadcast_action(path_suffix, server_urls, ssl_context = nil, http_callback)
      response = nil
      response_error = nil
      last_success = nil
      config = Puppet::Util::Puppetdb.config
      successful_submit_count = 0

      for server_url in server_urls
        route = concat_url_snippets(server_url.to_s, path_suffix)

        request_exception = with_http_error_logging(server_url, route) {
          http = Puppet.runtime[:http]

          response = Timeout.timeout(config.server_url_timeout) do
            begin
              http_callback.call(http, URI(route), ssl_context)
            rescue Puppet::HTTP::ResponseError => e
              e.response
            end
          end
        }

        if request_exception.nil?
          response_error = check_http_response(response, server_url, route)
          if response_error.nil?
            successful_submit_count += 1
            last_success = response
          end
        end
      end

      if successful_submit_count < config.min_successful_submissions or last_success.nil?
        raise_request_error(response, response_error, path_suffix)
      end

      last_success
    end

    # Setup an http connection, provide a block that will do something with that http
    # connection. The block should be a two argument block, accepting the connection (which
    # you can call get or post on for example) and the properly constructed path, which
    # will be the concatenated version of any url_prefix and the path passed in.
    #
    # @param path_suffix [String] path for the get/post of the http action
    # @param request_type [Symbol] :query or :command
    # @param http_callback [Proc] proc containing the code calling the action on the http connection
    # @return [Response] returns http response
    def self.action(path_suffix, request_mode, &http_callback)
      config = Puppet::Util::Puppetdb.config

      if config.verify_client_certificate
        ssl_context = nil
      else
        require 'puppet/ssl/ssl_provider'

        # If we're not doing full client validation, we at least check the CA.
        # This hands off settings to puppet.conf for the CA cert path, CRL and revocation checks
        cert_provider = Puppet::X509::CertProvider.new
        cacerts = cert_provider.load_cacerts(required: true)

        case Puppet[:certificate_revocation]
        when :chain, :leaf
          crls = cert_provider.load_crls(required: true)
        else
          crls = []
        end

        ssl_provider = Puppet::SSL::SSLProvider.new
        ssl_context = ssl_provider.create_root_context(cacerts: cacerts, crls: crls, revocation: Puppet[:certificate_revocation])
      end

      case request_mode
      when :query
        self.failover_action(path_suffix, config.server_urls, config.sticky_read_failover, ssl_context, http_callback)
      when :command
        submit_server_urls = config.server_urls + config.submit_only_server_urls
        if config.command_broadcast
          self.broadcast_action(path_suffix, submit_server_urls, ssl_context, http_callback)
        else
          self.failover_action(path_suffix, submit_server_urls, false, ssl_context, http_callback)
        end
      else
        raise Puppet::Error, "Unknown request mode: #{request_mode}"
      end
    end

    def self.reset_query_failover()
      @@last_good_query_server_url_index.reset(0)
    end
  end

  class NotFoundError < Puppet::Error
  end
end
