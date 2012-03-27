dir = File.expand_path(File.dirname(__FILE__))
$LOAD_PATH.unshift File.join(dir, "../lib")

require 'puppet'
require 'mocha'
gem 'rspec', '>=2.0.0'
require 'rspec/expectations'

# TODO: this is a hack;  here because we are about to require some files that
#   expect a module by this name to exist.
module PuppetSpec
end

# TODO: another hack: assumes that you have the puppet source checked out alongside the puppet-grayskull source
$LOAD_PATH.unshift File.join(dir, "../../../puppet/spec/lib")
require 'puppet_spec/settings'

RSpec.configure do |config|
  config.mock_with :mocha

  config.before :each do

    # Initialize "app defaults" settings to a good set of test values
    PuppetSpec::Settings::TEST_APP_DEFAULTS.each do |key, value|
      Puppet.settings.set_value(key, value, :application_defaults)
    end

    @logs = []
    Puppet::Util::Log.newdestination(Puppet::Test::LogCollector.new(@logs))

    @log_level = Puppet::Util::Log.level
  end

  config.after :each do
    Puppet.settings.send(:clear_everything_for_tests)

    Puppet::Node::Environment.clear
    Puppet::Util::Storage.clear

    @logs.clear
    Puppet::Util::Log.close_all
    Puppet::Util::Log.level = @log_level
  end
end
