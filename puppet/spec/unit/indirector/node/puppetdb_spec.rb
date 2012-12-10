#!/usr/bin/env rspec

require 'spec_helper'

require 'puppet/indirector/node/puppetdb'
require 'puppet/util/puppetdb/command_names'

describe Puppet::Node::Puppetdb do

  CommandDeactivateNode = Puppet::Util::Puppetdb::CommandNames::CommandDeactivateNode

  before :each do
    Puppet::Node.indirection.stubs(:terminus).returns(subject)
  end

  let(:node) { "something.example.com" }

  def destroy
    Puppet::Node.indirection.destroy(node)
  end

  describe "#destroy" do
    let(:response) { Net::HTTPOK.new('1.1', 200, 'OK') }

    it "should POST a '#{CommandDeactivateNode}' command as a URL-encoded PSON string" do
      response.stubs(:body).returns '{"uuid": "a UUID"}'

      payload = {
        :command => CommandDeactivateNode,
        :version => 1,
        :payload => node.to_pson,
      }.to_pson

      subject.expects(:http_post).with do |request,uri,body,headers|
        body =~ /payload=(.+)/
        @sent_payload = $1
      end.returns response

      destroy

      CGI.unescape(@sent_payload).should == payload
    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      response['x-deprecation'] = 'A horrible deprecation warning!'
      response.stubs(:body).returns '{"uuid": "a UUID"}'

      Puppet.expects(:deprecation_warning).with do |msg|
        msg =~ /A horrible deprecation warning!/
      end

      subject.stubs(:http_post).returns response

      destroy
    end
  end
end
