dir = File.expand_path(File.dirname(__FILE__))
$LOAD_PATH.unshift File.join(dir, "../lib")

require 'puppet'
require 'mocha'
gem 'rspec', '>=2.0.0'
require 'rspec/expectations'

RSpec.configure do |config|
  config.mock_with :mocha

  config.before :each do
    Puppet.settings.send(:initialize_everything_for_tests)

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
