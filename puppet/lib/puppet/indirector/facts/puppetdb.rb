require 'uri'
require 'puppet/node/facts'
require 'puppet/indirector/rest'
require 'puppet/util/puppetdb'
require 'json'
require 'time'

class Puppet::Node::Facts::Puppetdb < Puppet::Indirector::REST
  include Puppet::Util::Puppetdb
  include Puppet::Util::Puppetdb::CommandNames

  def get_trusted_info(node)
    trusted = Puppet.lookup(:trusted_information) do
      Puppet::Context::TrustedInformation.local(request.node)
    end
    trusted.to_h
  end

  def maybe_strip_internal(facts)
    if Puppet::Node::Facts.method_defined? :strip_internal
      facts.strip_internal
    else
      facts.values
    end
  end

  def save(request)
    profile("facts#save", [:puppetdb, :facts, :save, request.key]) do
      payload = profile("Encode facts command submission payload",
                        [:puppetdb, :facts, :encode]) do
        facts = request.instance.dup
        facts.values = facts.strip_internal.dup

        if ! Puppet::Util::Puppetdb.puppet3compat? || Puppet[:trusted_node_data]
          facts.values[:trusted] = get_trusted_info(request.node)
        end
        {
          "name" => facts.name,
          "values" => facts.values,
          # PDB-453: we call to_s to avoid a 'stack level too deep' error
          # when we attempt to use ActiveSupport 2.3.16 on RHEL 5 with
          # legacy storeconfigs.
          "environment" => request.options[:environment] || request.environment.to_s,
          "producer_timestamp" => request.options[:producer_timestamp] || Time.now.iso8601(5),
        }
      end

      submit_command(request.key, payload, CommandReplaceFacts, 4)
    end
  end

  def find(request)
    profile("facts#find", [:puppetdb, :facts, :find, request.key]) do
      begin
        response = Http.action("/v4/nodes/#{CGI.escape(request.key)}/facts") do |http_instance, path|
          profile("Query for nodes facts: #{URI.unescape(path)}",
                  [:puppetdb, :facts, :find, :query_nodes, request.key]) do
            http_instance.get(path, headers)
          end
        end
        log_x_deprecation_header(response)

        if response.is_a? Net::HTTPSuccess
          profile("Parse fact query response (size: #{response.body.size})",
                  [:puppetdb, :facts, :find, :parse_response, request.key]) do
            result = JSON.parse(response.body)
            # Note: the Inventory Service API appears to expect us to return nil here
            # if the node isn't found.  However, PuppetDB returns an empty array in
            # this case; for now we will just look for that condition and assume that
            # it means that the node wasn't found, so we will return nil.  In the
            # future we may want to improve the logic such that we can distinguish
            # between the "node not found" and the "no facts for this node" cases.
            if result.empty?
              return nil
            end
            facts = result.inject({}) do |a,h|
              a.merge(h['name'] => h['value'])
            end
            Puppet::Node::Facts.new(request.key, facts)
          end
        else
          # Newline characters cause an HTTP error, so strip them
          raise "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
        end
      rescue => e
        raise Puppet::Error, "Failed to find facts from PuppetDB at #{self.class.server}:#{self.class.port}: #{e}"
      end
    end
  end

  # Search for nodes matching a set of fact constraints. The constraints are
  # specified as a hash of the form:
  #
  # `{type.name.operator => value`
  #
  # The only accepted `type` is 'facts'.
  #
  # `name` must be the fact name to query against.
  #
  # `operator` may be one of {eq, ne, lt, gt, le, ge}, and will default to 'eq'
  # if unspecified.
  def search(request)
    profile("facts#search", [:puppetdb, :facts, :search, request.key]) do
      return [] unless request.options
      operator_map = {
        'eq' => '=',
        'gt' => '>',
        'lt' => '<',
        'ge' => '>=',
        'le' => '<=',
      }
      filters = request.options.sort.map do |key,value|
        type, name, operator = key.to_s.split('.')
        operator ||= 'eq'
        raise Puppet::Error, "Fact search against keys of type '#{type}' is unsupported" unless type == 'facts'
        if operator == 'ne'
          ['not', ['=', ['fact', name], value]]
        else
          [operator_map[operator], ['fact', name], value]
        end
      end

      query = ["and"] + filters
      query_param = CGI.escape(query.to_json)

      begin
        response = Http.action("/v4/nodes?query=#{query_param}") do |http_instance, path|
          profile("Fact query request: #{URI.unescape(path)}",
                  [:puppetdb, :facts, :search, :query_request, request.key]) do
            http_instance.get(path, headers)
          end
        end
        log_x_deprecation_header(response)

        if response.is_a? Net::HTTPSuccess
          profile("Parse fact query response (size: #{response.body.size})",
                  [:puppetdb, :facts, :search, :parse_query_response, request.key,]) do
            JSON.parse(response.body).collect {|s| s["name"]}
          end
        else
          # Newline characters cause an HTTP error, so strip them
          raise "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
        end
      rescue => e
        raise Puppet::Util::Puppetdb::InventorySearchError, e.message
      end
    end
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end
end
