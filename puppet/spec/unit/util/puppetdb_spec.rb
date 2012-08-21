#!/usr/bin/env rspec
# encoding: UTF-8

require 'spec_helper'

require 'puppet/util/puppetdb'

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

    let(:config) { File.join(confdir, 'puppetdb.conf') }

    before :each do
      Puppet[:confdir] = confdir
    end

    after :each do
      FileUtils.rm_rf(confdir)
    end

    describe "with no config file" do
      it "should use the default server and port" do
        described_class.load_puppetdb_config.should == ['puppetdb', 8081]
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
        described_class.load_puppetdb_config.should == ['main_server', 1234]
      end

      it "should use the default if no value is specified" do
        write_config ''

        described_class.load_puppetdb_config.should == ['puppetdb', 8081]
      end

      it "should be insensitive to whitespace" do
        write_config <<CONF
[main]
    server = main_server
      port    =  1234
CONF

        described_class.load_puppetdb_config.should == ['main_server', 1234]
      end

      it "should accept valid hostnames" do
        write_config <<CONF
[main]
server = foo.example-thing.com
port = 8081
CONF

        described_class.load_puppetdb_config.should == ['foo.example-thing.com', 8081]
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
    end
  end

end
