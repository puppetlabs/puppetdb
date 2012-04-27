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
      it "should use the default server and port for every terminus" do
        described_class.load_puppetdb_config.should == ['puppetdb', 8080]
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
CONF
        end
      end

      it "should use the config value if specified" do
        described_class.load_puppetdb_config.should == ['main_server', 1234]
      end

      it "should use the default if no value is specified" do
        File.truncate(config, 0)

        described_class.load_puppetdb_config.should == ['puppetdb', 8080]
      end
    end
  end

  describe "#utf8_string" do
    describe "on ruby 1.8", :if => RUBY_VERSION =~ /^1.8/ do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string"
        subject.utf8_string(str).should == str
      end

      it "should convert from non-overlapping latin-1 with a warning" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "a latin-1 string \xd6"
        subject.utf8_string(str).should == "a latin-1 string "
      end

      it "should remove invalid bytes and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff"
        subject.utf8_string(str).should == "an invalid utf-8 string "
      end

      it "should return a valid utf-8 string without warning" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string \xc3\x96"
        subject.utf8_string(str).should == str
      end
    end

    describe "on ruby > 1.8", :if => RUBY_VERSION !~ /^1.8/ do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string".force_encoding('us-ascii')
        subject.utf8_string(str).should == str
      end

      it "should convert from latin-1 without a warning" do
        Puppet.expects(:warning).never

        str = "a latin-1 string \xd6".force_encoding('iso-8859-1')
        subject.utf8_string(str).should == "a latin-1 string Ö"
      end

      # UndefinedConversionError
      it "should replace undefined characters and warn when converting from binary" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid binary string \xff".force_encoding('binary')
        # \ufffd == unicode replacement character
        subject.utf8_string(str).should == "an invalid binary string \ufffd"
      end

      # InvalidByteSequenceError
      it "should remove invalid bytes and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff".force_encoding('utf-8')
        subject.utf8_string(str).should == "an invalid utf-8 string "
      end

      it "should leave the string alone if it's valid UTF-8" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string".force_encoding('utf-8')
        subject.utf8_string(str).should == str
      end
    end
  end
end
