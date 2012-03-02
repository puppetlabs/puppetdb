#!/usr/bin/env rspec
# encoding: UTF-8

require 'spec_helper'

require 'puppet/indirector/catalog/grayskull'

describe Puppet::Resource::Catalog::Grayskull do
  describe "#save" do
    let(:catalog) do
      cat = Puppet::Resource::Catalog.new('foo')
      cat.add_resource(Puppet::Resource.new(:file, 'my_file'))
      cat
    end

    def save
      subject.save(Puppet::Resource::Catalog.indirection.request(:save, catalog.name, catalog))
    end

    it "should POST the catalog command as a URL-encoded PSON string" do
      payload = {
        :command => "replace catalog",
        :version => 1,
        :payload => catalog.to_pson,
      }.to_pson

      subject.expects(:http_post).with do |request,uri,body,headers|
        body =~ /payload=(.+)/
        @sent_payload = $1
      end

      save

      CGI.unescape(@sent_payload).should == payload
    end
  end

  describe "#munge_catalog" do
    let(:catalog) do
      Puppet::Resource::Catalog.new('foo')
    end

    it "should add namevar to aliases if it's not already present" do
      name = 'with a different name'
      notify = Puppet::Resource.new(:notify, 'notify_me', :parameters => {:name => name})

      catalog.add_resource(notify)

      hash = subject.munge_catalog(catalog)

      resource = hash['data']['resources'].find do |res|
        res['type'] == 'Notify' and res['title'] == 'notify_me'
      end

      resource.should_not be_nil
      resource['parameters']['alias'].should include(name)
    end
  end

  describe ".utf8_string" do
    describe "on ruby 1.8", :if => RUBY_VERSION =~ /^1.8/ do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string"
        described_class.utf8_string(str).should == str
      end

      it "should convert from non-overlapping latin-1 with a warning" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "a latin-1 string \xd6"
        described_class.utf8_string(str).should == "a latin-1 string "
      end

      it "should remove invalid bytes and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff"
        described_class.utf8_string(str).should == "an invalid utf-8 string "
      end

      it "should return a valid utf-8 string without warning" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string \xc3\x96"
        described_class.utf8_string(str).should == str
      end
    end

    describe "on ruby > 1.8", :if => RUBY_VERSION !~ /^1.8/ do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string".force_encoding('us-ascii')
        described_class.utf8_string(str).should == str
      end

      it "should convert from latin-1 without a warning" do
        Puppet.expects(:warning).never

        str = "a latin-1 string \xd6".force_encoding('iso-8859-1')
        described_class.utf8_string(str).should == "a latin-1 string Ö"
      end

      # UndefinedConversionError
      it "should replace undefined characters and warn when converting from binary" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid binary string \xff".force_encoding('binary')
        # \ufffd == unicode replacement character
        described_class.utf8_string(str).should == "an invalid binary string \ufffd"
      end

      # InvalidByteSequenceError
      it "should remove invalid bytes and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff".force_encoding('utf-8')
        described_class.utf8_string(str).should == "an invalid utf-8 string "
      end

      it "should leave the string alone if it's valid UTF-8" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string".force_encoding('utf-8')
        described_class.utf8_string(str).should == str
      end
    end
  end
end
