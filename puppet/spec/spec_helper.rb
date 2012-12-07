dir = File.expand_path(File.dirname(__FILE__))
$LOAD_PATH.unshift File.join(dir, "../lib")
# Maybe puppetlabs_spec_helper is in a directory next to puppetdb. If not, we
# don't fail any worse than we already would.
$LOAD_PATH.push File.join(dir, "../../../puppetlabs_spec_helper")

require 'rspec'
require 'puppetlabs_spec_helper/puppet_spec_helper'
require 'tmpdir'
require 'fileutils'
require 'puppet'
require 'puppet/util/log'


RSpec.configure do |config|

  config.before :all do
    # Now that we are spooling commands to disk, we need to set up a temp dir
    # where we can safely write them.
    $puppetdb_tmp_vardir = Dir.mktmpdir("puppetdb-test-vardir")
  end

  config.before :each do
    # Puppet's spec_helper clears settings after each test, so we need to reset
    # the vardir to point to our tmpdir
    Puppet[:vardir] = $puppetdb_tmp_vardir

    @logs = []
    Puppet::Util::Log.level = :info
    Puppet::Util::Log.newdestination(Puppet::Test::LogCollector.new(@logs))

    def test_logs
      @logs.map(&:message)
    end

  end

  config.after :each do
    # Clear out our tmpdir after each test
    FileUtils.rmtree($puppetdb_tmp_vardir)
    FileUtils.mkdir_p($puppetdb_tmp_vardir)
  end

  config.after :all do
    # Finally, remove our tmpdir
    FileUtils.rmtree($puppetdb_tmp_vardir)
  end
end
