dir = File.expand_path(File.dirname(__FILE__))
$LOAD_PATH.unshift File.join(dir, "../lib")
# Maybe puppetlabs_spec_helper is in a directory next to puppetdb. If not, we
# don't fail any worse than we already would.
$LOAD_PATH.push File.join(dir, "../../../puppetlabs_spec_helper")

require 'rspec'
require 'rspec/expectations'
require 'puppetlabs_spec_helper/puppet_spec_helper'
require 'tmpdir'
require 'fileutils'
require 'puppet'
require 'puppet/util/puppetdb'
require 'puppet/util/log'

def create_environmentdir(environment)
  if not Puppet::Util::Puppetdb.puppet3compat?
    envdir = File.join(Puppet[:environmentpath], environment)
    if not Dir.exists?(envdir)
      Dir.mkdir(envdir)
    end
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

end
