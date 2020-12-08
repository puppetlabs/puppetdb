require 'puppet/indirector/rest'
require 'puppet/util/puppetdb'
require 'json'
require 'cgi'

class Puppet::Resource::Puppetdb < Puppet::Indirector::REST
  include Puppet::Util::Puppetdb

  def search(request)
    profile("resource#search",
            [:puppetdb, :resource, :search, request.key]) do
      type   = request.key
      host   = request.options[:host]
      filter = request.options[:filter]
      scope  = request.options[:scope]

      # At minimum, we want to filter to the right type of exported resources.
      expr = ['and',
               ['=', 'type', type],
               ['=', 'exported', true],
               ['not',
                 ['=', 'certname', host]]]

      filter_expr = build_expression(filter)
      expr << filter_expr if filter_expr

      query_param = CGI.escape(expr.to_json)

      uri_ref = nil
      begin
        response = Http.action("/pdb/query/v4/resources?query=#{query_param}", :query) do |http_instance, uri, ssl_context|
          uri_ref = uri
          profile("Resources query: #{CGI.unescape(uri.path)}",
                  [:puppetdb, :resource, :search, :query, request.key]) do
            http_instance.get(uri, **{headers: headers,
                                      options: {:metric_id => [:puppetdb, :resource, :search],
                                                ssl_context: ssl_context}})
          end
        end

        log_x_deprecation_header(response)

        unless response.success?
          # Newline characters cause an HTTP error, so strip them
          raise Puppet::Error, "[#{response.code} #{response.reason}] #{response.body.gsub(/[\r\n]/, '')}"
        end
      rescue => e
        raise Puppet::Error, "Could not retrieve resources from the PuppetDB at #{uri_ref.scheme}://#{uri_ref.host}:#{uri_ref.port}: #{e}"
      end

      resources = profile("Parse resource query response (size: #{response.body.size})",
                          [:puppetdb, :resource, :search, :parse_query_response, request.key]) do
        JSON.load(response.body)
      end

      profile("Build up collected resource objects (count: #{resources.count})",
              [:puppetdb, :resource, :search, :build_up_collected_objects, request.key]) do
        resources.map do |res|
          params = res['parameters'] || {}
          params = params.map do |name,value|
            Puppet::Parser::Resource::Param.new(:name => name, :value => value)
          end
          attrs = {:parameters => params, :scope => scope}

          t = res['type']
          t = Puppet::Pops::Evaluator::Runtime3ResourceSupport.find_resource_type(scope, t) unless t == 'class' || t == 'node'
          result = Puppet::Parser::Resource.new(t, res['title'], attrs)
          result.collector_id = "#{res['certname']}|#{res['type']}|#{res['title']}"
          result
        end
      end
    end
  end

  def build_expression(filter)
    return nil unless filter

    lhs, op, rhs = filter

    case op
    when '==', '!='
      build_predicate(op, lhs, rhs)
    when 'and', 'or'
      build_join(op, lhs, rhs)
    else
      raise Puppet::Error, "Operator #{op} in #{filter.inspect} not supported"
    end
  end

  def build_predicate(op, field, value)
    # Title and tag aren't parameters, so we have to special-case them.
    expr = case field
           when "tag"
             # Tag queries are case-insensitive, so downcase them
             ["=", "tag", value.downcase]
           when "title"
             ["=", "title", value]
           else
             ["=", ['parameter', field], value]
           end

    op == '!=' ? ['not', expr] : expr
  end

  def build_join(op, lhs, rhs)
    lhs = build_expression(lhs)
    rhs = build_expression(rhs)

    [op, lhs, rhs]
  end

  def headers
    {'Accept' => 'application/json'}
  end
end
