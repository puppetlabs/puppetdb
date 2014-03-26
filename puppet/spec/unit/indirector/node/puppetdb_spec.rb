#!/usr/bin/env rspec

require 'spec_helper'

require 'puppet/indirector/node/puppetdb'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/http_client'
require 'json'

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
    let(:http)     { mock 'http' }
    before :each do
      Puppet::Util::Puppetdb::HttpClient.expects(:instance).returns http
    end

    it "should POST a '#{CommandDeactivateNode}' command as a URL-encoded JSON string" do
      response.stubs(:body).returns '{"uuid": "a UUID"}'

      payload = {
        :command => CommandDeactivateNode,
        :version => 1,
        :payload => node.to_json,
      }.to_json

      http.expects(:post).with do |uri,body,headers|
        expect(body).to eq(payload)
      end.returns response

      destroy
    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      response['x-deprecation'] = 'A horrible deprecation warning!'
      response.stubs(:body).returns '{"uuid": "a UUID"}'

      Puppet.expects(:deprecation_warning).with do |msg|
        msg =~ /A horrible deprecation warning!/
      end

      http.stubs(:post).returns response

      destroy
    end
  end
end
