dir = File.expand_path(File.dirname(__FILE__))
$LOAD_PATH.unshift File.join(dir, "../lib")
# Maybe puppetlabs_spec_helper is in a directory next to puppetdb. If not, we
# don't fail any worse than we already would.
$LOAD_PATH.push File.join(dir, "../../../puppetlabs_spec_helper")

require 'cgi'
require 'rspec'
require 'rspec/expectations'
require 'puppetlabs_spec_helper/puppet_spec_helper'
require 'tmpdir'
require 'fileutils'
require 'puppet'
require 'puppet/util/puppetdb'
require 'puppet/util/log'


def create_environmentdir(environment)
  envdir = File.join(Puppet[:environmentpath], environment)
  if not Dir.exists?(envdir)
    Dir.mkdir(envdir)
  end
end

def extract_producer_timestamp(command)
  DateTime.parse(command["producer_timestamp"]).to_time.to_i
end

def assert_command_req(expected_payload, actual_payload)
  req = JSON.parse(actual_payload)
  actual_producer_timestamp = extract_producer_timestamp(req)
  req.delete("producer_timestamp")
  req == expected_payload &&
    actual_producer_timestamp <= Time.now.to_i
end

def assert_valid_producer_ts(path)
  _, param_str = path.split "?"
  params = CGI::parse(param_str)
  return false if params["producer-timestamp"].size != 1
  Time.iso8601(params["producer-timestamp"].first)
end

def create_http_response(url, nethttp)
  if Puppet::PUPPETVERSION.to_f < 7
    Puppet::HTTP::Response.new(nethttp, url)
  else
    Puppet::HTTP::ResponseNetHTTP.new(url, nethttp)
  end
end

RSpec.configure do |config|

  config.before :each do
    @logs = []
    Puppet::Util::Log.level = :info
    Puppet::Util::Log.newdestination(Puppet::Test::LogCollector.new(@logs))

    def test_logs
      @logs.map(&:message)
    end

  end

  config.expect_with :rspec do |c|
    c.syntax = [:should, :expect]
  end

end
