require 'puppet/node'
require 'puppet/indirector/rest'
require 'puppet/util/puppetdb'

class Puppet::Node::Puppetdb < Puppet::Indirector::REST
  include Puppet::Util::Puppetdb

  def find(request)
  end

  def save(request)
  end

  def destroy(request)
    payload = { :certname => request.key,
                :producer_timestamp => request.options[:producer_timestamp] || Time.now.iso8601(5) }
    submit_command(request.key, payload, CommandDeactivateNode, 3)
  end
end
