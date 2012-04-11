#!/usr/bin/env rspec

require 'spec_helper'

require 'puppet/indirector/node/puppetdb'

describe Puppet::Node::Puppetdb do
  let(:node) { "something.example.com" }

  def destroy
    subject.destroy(Puppet::Node.indirection.request(:destroy, node))
  end

  describe "#destroy" do
    it "should POST a 'deactive node' command as a URL-encoded PSON string" do
      payload = {
        :command => "deactivate node",
        :version => 1,
        :payload => node.to_pson,
      }.to_pson

      subject.expects(:http_post).with do |request,uri,body,headers|
        body =~ /payload=(.+)/
        @sent_payload = $1
      end

      destroy

      CGI.unescape(@sent_payload).should == payload
    end
  end
end
