require 'puppet/node/facts'
require 'puppet/indirector/rest'
require 'puppet/util/puppetdb'

class Puppet::Node::Facts::Puppetdb < Puppet::Indirector::REST
  include Puppet::Util::Puppetdb
  include Puppet::Util::Puppetdb::CommandNames

  def save(request)
    facts = request.instance.dup
    facts.values = facts.values.dup
    facts.stringify

    submit_command(request.key, facts.to_pson, CommandReplaceFacts, 1)
  end

  def find(request)
    begin
      response = http_get(request, "/v2/nodes/#{CGI.escape(request.key)}/facts", headers)
      log_x_deprecation_header(response)

      if response.is_a? Net::HTTPSuccess
        result = PSON.parse(response.body)
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
      else
        # Newline characters cause an HTTP error, so strip them
        raise "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
      end
    rescue => e
      raise Puppet::Error, "Failed to find facts from PuppetDB at #{self.class.server}:#{self.class.port}: #{e}"
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
    query_param = CGI.escape(query.to_pson)

    begin
      response = http_get(request, "/v2/nodes?query=#{query_param}", headers)
      log_x_deprecation_header(response)

      if response.is_a? Net::HTTPSuccess
        PSON.parse(response.body).collect {|s| s["name"]}
      else
        # Newline characters cause an HTTP error, so strip them
        raise "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
      end
    rescue => e
      raise Puppet::Error, "Could not perform inventory search from PuppetDB at #{self.class.server}:#{self.class.port}: #{e}"
    end
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end
end
