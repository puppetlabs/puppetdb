require 'puppet/resource/catalog'
require 'puppet/indirector/rest'

class Puppet::Resource::Catalog::Grayskull < Puppet::Indirector::REST
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
    payload = URI.encode(message(request.instance).to_pson)

    http_post(request, "/commands", "payload=#{payload}", headers)
  end

  def find(request)
    nil
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded",
    }
  end

  def message(instance)
    {
      :command => "replace catalog",
      :version => 1,
      :payload => instance,
    }
  end
end
