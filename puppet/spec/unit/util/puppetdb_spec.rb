#!/usr/bin/env rspec
# encoding: UTF-8

require 'spec_helper'

require 'puppet/util/puppetdb'

# Create a local copy of these constants so that we don't have to refer to them
# by their full namespaced name
CommandReplaceCatalog   = Puppet::Util::Puppetdb::CommandReplaceCatalog
CommandReplaceFacts     = Puppet::Util::Puppetdb::CommandReplaceFacts
CommandStoreReport      = Puppet::Util::Puppetdb::CommandStoreReport

describe Puppet::Util::Puppetdb do
  subject { Object.new.extend described_class }

  describe ".load_puppetdb_config" do
    let(:confdir) do
      temp = Tempfile.new('confdir')
      path = temp.path
      temp.close!
      Dir.mkdir(path)
      path
    end

    before :each do
      Puppet[:confdir] = confdir
    end

    after :each do
      FileUtils.rm_rf(confdir)
    end

    describe "with no config file" do
      it "should use the default server and port" do
        config = described_class.load_puppetdb_config
        config[:server].should == 'puppetdb'
        config[:port].should == 8081
      end

      it "should use the default settings for command spooling" do
        config = described_class.load_puppetdb_config
        config[CommandReplaceCatalog][:spool].should == false
        config[CommandReplaceFacts][:spool].should == false
        config[CommandStoreReport][:spool].should == true
      end
    end

    describe "with a config file" do
      def write_config(content)
        conf = File.join(confdir, 'puppetdb.conf')
        File.open(conf, 'w') { |file| file.print(content) }
      end

      it "should not explode if there are comments in the config file" do
        write_config <<CONF
#this is a comment
 ; so is this
[main]
server = main_server
   # yet another comment
port = 1234
CONF
        expect { described_class.load_puppetdb_config }.to_not raise_error
      end


      it "should use the config value if specified" do
        write_config <<CONF
[main]
server = main_server
port = 1234
CONF
        config = described_class.load_puppetdb_config
        config[:server].should == 'main_server'
        config[:port].should == 1234
      end

      it "should use the default if no value is specified" do
        write_config ''

        config = described_class.load_puppetdb_config
        config[:server].should == 'puppetdb'
        config[:port].should == 8081
      end

      it "should be insensitive to whitespace" do
        write_config <<CONF
[main]
    server = main_server
      port    =  1234
CONF
        config = described_class.load_puppetdb_config
        config[:server].should == 'main_server'
        config[:port].should == 1234
      end

      it "should accept valid hostnames" do
        write_config <<CONF
[main]
server = foo.example-thing.com
port = 8081
CONF

        config = described_class.load_puppetdb_config
        config[:server].should == 'foo.example-thing.com'
        config[:port].should == 8081
      end

      it "should raise if a setting is outside of a section" do
        write_config 'foo = bar'

        expect do
          described_class.load_puppetdb_config
        end.to raise_error(/Setting 'foo = bar' is illegal outside of section/)
      end

      it "should raise if an illegal line is encountered" do
        write_config 'foo bar baz'

        expect do
          described_class.load_puppetdb_config
        end.to raise_error(/Unparseable line 'foo bar baz'/)
      end

      context "command-specific settings" do
        it "should use the defaults if no value is specified" do
          write_config ''

          config = described_class.load_puppetdb_config
          config[CommandReplaceCatalog][:spool].should == false
          config[CommandReplaceFacts][:spool].should == false
          config[CommandStoreReport][:spool].should == true
        end

        it "should use the config value if specified" do
          write_config <<CONF
[facts]
spool = true

[catalogs]
spool = true

[reports]
spool = false
CONF
          config = described_class.load_puppetdb_config
          config[CommandReplaceCatalog][:spool].should == true
          config[CommandReplaceFacts][:spool].should == true
          config[CommandStoreReport][:spool].should == false
        end
      end

    end
  end

end
