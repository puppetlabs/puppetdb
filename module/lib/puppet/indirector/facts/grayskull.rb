require 'puppet/node/facts'
require 'puppet/indirector/rest'
require 'digest'

class Puppet::Node::Facts::Grayskull < Puppet::Indirector::REST
  # These settings don't exist in Puppet yet, so we have to hard-code them for now.
  #use_server_setting :grayskull_server
  #use_port_setting :grayskull_port

  def self.server
    "grayskull"
  end

  def self.port
    8080
  end

  def save(request)
    msg = message(request.instance).to_pson
    msg = Puppet::Resource::Catalog::Grayskull.utf8_string(msg)
    checksum = Digest::SHA1.hexdigest(msg)
    payload = CGI.escape(msg)

    http_post(request, "/commands", "checksum=#{checksum}&payload=#{payload}", headers)
  end

  def find(request)
    nil
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
