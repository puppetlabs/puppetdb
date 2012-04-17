require 'puppet/node/facts'
require 'puppet/indirector/rest'
require 'digest'

class Puppet::Node::Facts::Puppetdb < Puppet::Indirector::REST
  # These settings don't exist in Puppet yet, so we have to use a hack with an
  #use_server_setting :puppetdb_server
  #use_port_setting :puppetdb_port

  def initialize
    # Make sure we've loaded the config file
    Puppet.features.puppetdb?
  end

  def save(request)
    facts = request.instance.dup
    facts.values = facts.values.dup
    facts.stringify

    msg = message(facts).to_pson
    msg = Puppet::Resource::Catalog::Puppetdb.utf8_string(msg)
    checksum = Digest::SHA1.hexdigest(msg)
    payload = CGI.escape(msg)

    http_post(request, "/commands", "checksum=#{checksum}&payload=#{payload}", headers)
  end

  def find(request)
    begin
      response = http_get(request, "/facts/#{request.key}", headers)

      if response.is_a? Net::HTTPSuccess
        result = PSON.parse(response.body)
        Puppet::Node::Facts.new(result['name'], result['facts'])
      end
    rescue => e
      nil
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

    response = http_get(request, "/nodes?query=#{query_param}", headers)

    if response.is_a? Net::HTTPSuccess
      PSON.parse(response.body)
    else
      raise Puppet::Error, "Could not perform inventory search: #{response.code} #{response.body}"
    end
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end

  def message(instance)
    {
      :command => "replace facts",
      :version => 1,
      :payload => instance.to_pson,
    }
  end
end
