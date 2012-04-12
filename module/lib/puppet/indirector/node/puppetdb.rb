require 'puppet/node'
require 'puppet/indirector/rest'

class Puppet::Node::Puppetdb < Puppet::Indirector::REST
  def initialize
    # Make sure we've loaded the config file
    Puppet.features.puppetdb?
  end

  def find(request)
  end

  def save(request)
  end

  def destroy(request)
    message = {
      :command => "deactivate node",
      :version => 1,
      :payload => request.key.to_pson,
    }.to_pson

    checksum = Digest::SHA1.hexdigest(message)
    payload = CGI.escape(message)

    http_post(request, "/commands", "checksum=#{checksum}&payload=#{payload}", headers)
    Puppet.info "Submitted deactivation command for #{request.key}"
    nil
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end
end
