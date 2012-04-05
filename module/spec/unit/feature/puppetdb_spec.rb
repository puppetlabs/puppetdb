#!/usr/bin/env rspec

require 'spec_helper'
require 'puppet/indirector/facts/puppetdb'
require 'puppet/indirector/catalog/puppetdb'
require 'puppet/indirector/resource/puppetdb'

describe "PuppetDB feature" do
  # Features are only executed when they're loaded, so we must forcibly reload
  # the file. :(
  def reload_feature
    load File.join(File.dirname(__FILE__), '../../../lib/puppet/feature/puppetdb.rb')
    Puppet.features.should be_puppetdb
  end

  let(:confdir) do
    temp = Tempfile.new('confdir')
    path = temp.path
    temp.close!
    Dir.mkdir(path)
    path
  end

  let(:config) { File.join(confdir, 'puppetdb.conf') }

  before :each do
    Puppet[:confdir] = confdir
  end

  after :each do
    FileUtils.rm_rf(confdir)
  end

  describe "with no config file" do
    it "should use the default server and port for every terminus" do
      reload_feature

      Puppet::Node::Facts::Puppetdb.server.should == 'puppetdb'
      Puppet::Node::Facts::Puppetdb.port.should == 8080
    end
  end

  describe "with a config file" do
    before :each do
      conf = File.join(confdir, 'puppetdb.conf')
      File.open(conf, 'w') do |file|
        file.print <<CONF
[main]
server = main_server
port = 1234

[facts]
server = facts_server
port = 5678
CONF
      end
    end

    it "should use the config value if specified" do
      reload_feature

      Puppet::Resource::Catalog::Puppetdb.server.should == 'main_server'
      Puppet::Resource::Catalog::Puppetdb.port.should == 1234
    end

    it "should use the default if no value is specified" do
      File.truncate(config, 0)

      reload_feature

      Puppet::Node::Facts::Puppetdb.server.should == 'puppetdb'
      Puppet::Node::Facts::Puppetdb.port.should == 8080
    end
  end
end
