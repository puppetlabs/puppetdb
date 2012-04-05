require 'puppet/indirector/rest'

class Puppet::Resource::Puppetdb < Puppet::Indirector::REST
  # These settings don't exist in Puppet yet, so we have to use a hack with an
  # external config file.
  #use_server_setting :puppetdb_server
  #use_port_setting :puppetdb_port

  def initialize
    # Make sure we've loaded the config file
    Puppet.features.puppetdb?
  end

  def search(request)
    type   = request.key
    host   = request.options[:host]
    filter = request.options[:filter]
    scope  = request.options[:scope]

    validate_filter(filter)

    # At minimum, we want to filter to the right type of exported resources.
    expr = ['and',
             ['=', 'type', type],
             ['=', 'exported', true],
             ['=', ['node', 'active'], true],
             ['not',
               ['=', ['node', 'name'], host]]]

    filter_expr = build_filter_expression(filter)
    expr << filter_expr if filter_expr

    query_string = "query=#{expr.to_pson}"

    begin
      response = http_get(request, "/resources?#{query_string}", headers)
    rescue => e
      raise Puppet::Error, "Could not retrieve resources from the PuppetDB server: #{e}"
    end

    resources = PSON.load(response.body)
    resources.map do |res|
      params = res['parameters'] || {}
      params = params.map do |name,value|
        Puppet::Parser::Resource::Param.new(:name => name, :value => value)
      end
      attrs = {:parameters => params, :scope => scope}
      Puppet::Parser::Resource.new(res['type'], res['title'], attrs)
    end
  end

  def validate_filter(filter)
    return true unless filter

    if filter[1] =~ /^(and|or)$/i
      raise Puppet::Error, "Complex search on StoreConfigs resources is not supported"
    elsif ! ['=', '==', '!='].include? filter[1]
      raise Puppet::Error, "Operator #{filter[1].inspect} in #{filter.inspect} not supported"
    end

    true
  end

  def build_filter_expression(filter)
    return nil unless filter

    equal_expr = ['=', ['parameter', filter.first], filter.last]

    filter[1] == '!=' ? ['not', equal_expr] : equal_expr
  end

  def headers
    {'Accept' => 'application/json'}
  end
end
