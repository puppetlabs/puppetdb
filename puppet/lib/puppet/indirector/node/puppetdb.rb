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
    current_time = Time.now
    payload = { :certname => request.key,
                :producer_timestamp => Puppet::Util::Puppetdb.to_wire_time(current_time) }

    submit_command(request.key, payload, CommandDeactivateNode, 3, current_time.clone.utc)
  end
end
