require 'puppet/resource/catalog'
require 'puppet/indirector/rest'
require 'digest'

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
    catalog = munge_catalog(request.instance)
    msg = message(catalog).to_pson
    checksum = Digest::SHA1.hexdigest(msg)
    payload = CGI.escape(msg)

    http_post(request, "/commands", "checksum=#{checksum}&payload=#{payload}", headers)
  end

  def find(request)
    nil
  end

  def munge_catalog(catalog)
    hash = catalog.to_pson_data_hash

    hash['data']['resources'].each do |resource|
      next unless resource['parameters']

      real_resource = catalog.resource(resource['type'], resource['title'])

      aliases = real_resource[:alias]

      case aliases
      when String
        aliases = [aliases]
      when nil
        aliases = []
      end

      name = real_resource[real_resource.send(:namevar)]
      unless name.nil? or real_resource.title == name or aliases.include?(name)
        aliases << name
      end

      resource['parameters']['alias'] = aliases
    end

    hash
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end

  def message(catalog)
    {
      :command => "replace catalog",
      :version => 1,
      :payload => catalog.to_pson,
    }
  end
end
