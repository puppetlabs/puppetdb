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

    submit_command(request.key, CommandDeactivateNode, 3, current_time.clone.utc) do
      {:certname => request.key,
       :producer_timestamp => Puppet::Util::Puppetdb.to_wire_time(current_time)}
    end
  end
end
