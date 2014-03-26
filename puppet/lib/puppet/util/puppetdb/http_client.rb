require 'puppet/util/puppetdb'
require 'puppet/network/http_pool'

class Puppet::Util::Puppetdb::HttpClient
  def self.instance(server, port)
    Puppet::Network::HttpPool.http_instance(server, port)
  end
end