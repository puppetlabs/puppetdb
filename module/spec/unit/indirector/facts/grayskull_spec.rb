#!/usr/bin/env rspec
require 'spec_helper'

require 'puppet/indirector/facts/grayskull'

describe Puppet::Node::Facts::Grayskull do
  describe "#save" do
    let(:facts) do
      Puppet::Node::Facts.new('foo')
    end

    def save
      subject.save(Puppet::Node::Facts.indirection.request(:save, facts.name, facts))
    end

    it "should POST the facts command as a URL-encoded PSON string" do
      payload = {
        :command => "replace facts",
        :version => 1,
        :payload => facts.to_pson,
      }.to_pson

      subject.expects(:http_post).with do |request,uri,body,headers|
        body =~ /payload=(.+)/
        @sent_payload = $1
      end

      save

      CGI.unescape(@sent_payload).should == payload
    end
  end
end
