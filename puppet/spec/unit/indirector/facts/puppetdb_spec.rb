#!/usr/bin/env rspec
require 'spec_helper'

require 'puppet/indirector/facts/puppetdb'
require 'puppet/util/puppetdb/command_names'

describe Puppet::Node::Facts::Puppetdb do

  CommandReplaceFacts = Puppet::Util::Puppetdb::CommandNames::CommandReplaceFacts

  before :each do
    Puppet::Util::Puppetdb.stubs(:server).returns 'localhost'
    Puppet::Util::Puppetdb.stubs(:port).returns 0
    Puppet::Node::Facts.indirection.stubs(:terminus).returns(subject)
  end

  describe "#save" do
    let(:response) { Net::HTTPOK.new('1.1', 200, 'OK') }
    let(:facts)    { Puppet::Node::Facts.new('foo') }
    let(:http)     { mock 'http' }

    before :each do
      Puppet::Network::HttpPool.expects(:http_instance).returns http
      response.stubs(:body).returns '{"uuid": "a UUID"}'
    end

    def save
      subject.save(Puppet::Node::Facts.indirection.request(:save, facts.name, facts))
    end

    it "should POST the facts command as a URL-encoded PSON string" do
      payload = {
        :command => CommandReplaceFacts,
        :version => 1,
        :payload => facts.to_pson,
      }.to_pson

      http.expects(:post).with do |uri, body, headers|
        body =~ /payload=(.+)/
        @sent_payload = $1
      end.returns response

      save

      CGI.unescape(@sent_payload).should == payload
    end

    it "should stringify fact values before submitting" do
      facts.values['something'] = 100

      http.expects(:post).with do |uri, body, headers|
        body =~ /payload=(.+)/
        @sent_payload = $1
      end.returns response

      save

      message = PSON.parse(CGI.unescape(@sent_payload))
      sent_facts = PSON.parse(message['payload'])

      # We shouldn't modify the original instance
      facts.values['something'].should == 100
      sent_facts['values']['something'].should == '100'
    end
  end

  describe "#find" do
    def find_facts()
      Puppet::Node::Facts.indirection.find('some_node')
    end

    it "should return the facts if they're found" do
      body = [{:node => 'some_node', :name => 'a', :value => '1'},
              {:node => 'some_node', :name => 'b', :value => '2'}].to_pson

      response = Net::HTTPOK.new('1.1', 200, 'OK')
      response.stubs(:body).returns body

      subject.stubs(:http_get).returns response

      result = find_facts
      result.should be_a(Puppet::Node::Facts)
      result.name.should == 'some_node'
      result.values.should include('a' => '1', 'b' => '2')
    end

    it "should return nil if no facts are found" do
      body = [].to_pson

      response = Net::HTTPOK.new('1.1', 200, 'OK')
      response.stubs(:body).returns body

      subject.stubs(:http_get).returns response

      find_facts.should be_nil
    end

    it "should fail if an HTTP error code is returned" do
      response = Net::HTTPForbidden.new('1.1', 403, "Forbidden")
      response.stubs(:body).returns ''

      subject.stubs(:http_get).returns response

      expect {
        find_facts
      }.to raise_error Puppet::Error, /\[403 Forbidden\]/
    end

    it "should fail if an error occurs" do
      subject.stubs(:http_get).raises Puppet::Error, "Everything is terrible!"

      expect {
        find_facts
      }.to raise_error Puppet::Error, /Everything is terrible!/
    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      response = Net::HTTPOK.new('1.1', 200, 'OK')
      response['x-deprecation'] = "This is deprecated!"

      body = [].to_pson

      response.stubs(:body).returns body

      subject.stubs(:http_get).returns response

      Puppet.expects(:deprecation_warning).with do |msg|
        msg =~ /This is deprecated!/
      end

      find_facts
    end
  end

  describe "#search" do
    def search_facts(query)
      Puppet::Node::Facts.indirection.search('facts', query)
    end
    let(:response) { Net::HTTPOK.new('1.1', 200, 'OK') }

    it "should return the nodes from the response" do
      args = {
        'facts.kernel.eq' => 'Linux',
      }

      response.stubs(:body).returns '["foo", "bar", "baz"]'
      response.stubs(:body).returns '[{"name": "foo", "deactivated": null, "catalog_timestamp": null, "facts_timestamp": null, "report_timestamp": null},
                                      {"name": "bar", "deactivated": null, "catalog_timestamp": null, "facts_timestamp": null, "report_timestamp": null},
                                      {"name": "baz", "deactivated": null, "catalog_timestamp": null, "facts_timestamp": null, "report_timestamp": null}]'
      subject.stubs(:http_get).returns response

      search_facts(args).should == ['foo', 'bar', 'baz']
    end

    it "should only allow searches against facts" do
      args = {
        'facts.kernel.eq' => 'Linux',
        'wrong.kernel.eq' => 'Linux',
      }

      expect do
        search_facts(args)
      end.to raise_error(Puppet::Error, /Fact search against keys of type 'wrong' is unsupported/)
    end

    it "should combine multiple terms with 'and'" do
      args = {
        'facts.kernel.eq' => 'Linux',
        'facts.uptime.eq' => '10 days',
      }

      query = CGI.escape(["and", ["=", ["fact", "kernel"], "Linux"],
                                 ["=", ["fact", "uptime"], "10 days"]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url.should == "/v2/nodes?query=#{query}"
      end.returns response

      search_facts(args)
    end

    it "should add 'not' to a != query" do
      args = {
        'facts.kernel.ne' => 'Linux',
      }

      query = CGI.escape(["and", ["not", ["=", ["fact", "kernel"], "Linux"]]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url.should == "/v2/nodes?query=#{query}"
      end.returns response

      search_facts(args)
    end

    it "should default the operator to = if one is not specified" do
      args = {
        'facts.kernel' => 'Linux',
      }

      query = CGI.escape(["and", ["=", ["fact", "kernel"], "Linux"]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url.should == "/v2/nodes?query=#{query}"
      end.returns response

      search_facts(args)
    end

    {
      'gt' => '>',
      'lt' => '<',
      'ge' => '>=',
      'le' => '<='
    }.each do |name, operator|
      it "should map '#{name}' to #{operator}" do
        args = {
          "facts.kernel.#{name}" => 'Linux',
        }

        query = CGI.escape(["and", [operator, ["fact", "kernel"], "Linux"]].to_pson)

        response.stubs(:body).returns '[]'

        subject.expects(:http_get).with do |_,url,_|
          url.should == "/v2/nodes?query=#{query}"
        end.returns response

        search_facts(args)
      end
    end

    it "should raise an error if a failure occurs" do
      response = Net::HTTPBadRequest.new('1.1', 400, 'Bad Request')
      response.stubs(:body).returns 'Something bad happened!'

      subject.stubs(:http_get).returns response

      expect do
        search_facts(nil)
      end.to raise_error(Puppet::Error, /Could not perform inventory search from PuppetDB at localhost:0: \[400 Bad Request\] Something bad happened!/)
    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      response['x-deprecation'] = "This is deprecated!"
      response.stubs(:body).returns '[]'

      subject.stubs(:http_get).returns response

      Puppet.expects(:deprecation_warning).with do |msg|
        msg =~ /This is deprecated!/
      end

      search_facts(nil)
    end
  end
end
