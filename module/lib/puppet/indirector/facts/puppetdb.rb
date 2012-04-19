require 'puppet/node/facts'
require 'puppet/indirector/rest'
require 'puppet/util/puppetdb'

class Puppet::Node::Facts::Puppetdb < Puppet::Indirector::REST
  include Puppet::Util::Puppetdb

  def save(request)
    facts = request.instance.dup
    facts.values = facts.values.dup
    facts.stringify

    submit_command(request, facts, 'replace facts', 1)
  end

  def find(request)
    begin
      response = http_get(request, "/facts/#{request.key}", headers)

      if response.is_a? Net::HTTPSuccess
        result = PSON.parse(response.body)
        Puppet::Node::Facts.new(result['name'], result['facts'])
      elsif response.is_a? Net::HTTPNotFound
        nil
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

    query = ["and", ["=", ["node", "active"], true]] + filters
    query_param = CGI.escape(query.to_pson)

    begin
      response = http_get(request, "/nodes?query=#{query_param}", headers)

      if response.is_a? Net::HTTPSuccess
        PSON.parse(response.body)
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
