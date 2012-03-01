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
    checksum = Digest::SHA1.hexdigest(msg)
    payload = CGI.escape(msg)

    http_post(request, "/commands", "checksum=#{checksum}&payload=#{payload}", headers)
  end

  def find(request)
    nil
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
