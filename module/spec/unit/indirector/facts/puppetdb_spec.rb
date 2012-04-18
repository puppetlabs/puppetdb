#!/usr/bin/env rspec
require 'spec_helper'

require 'puppet/indirector/facts/puppetdb'

describe Puppet::Node::Facts::Puppetdb do
  before :each do
    Puppet::Util::Puppetdb.stubs(:load_puppetdb_config).returns ['localhost', 0]
  end

  describe "#save" do
    let(:response) { Net::HTTPOK.new('1.1', 200, 'OK') }
    let(:facts) do
      Puppet::Node::Facts.new('foo')
    end

    before :each do
      response.stubs(:body).returns '{"uuid": "a UUID"}'
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
      end.returns response

      save

      CGI.unescape(@sent_payload).should == payload
    end

    it "should stringify fact values before submitting" do
      facts.values['something'] = 100

      payload = {
        :command => "replace facts",
        :version => 1,
        :payload => facts.to_pson,
      }.to_pson

      subject.expects(:http_post).with do |request,uri,body,headers|
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
    let(:request) { Puppet::Node::Facts.indirection.request(:find, 'facts') }

    it "should return the facts if they're found" do
      facts = {'a' => '1',
                'b' => '2'}
      body = {:name => 'some_node',
              :facts => facts}.to_pson

      response = Net::HTTPOK.new('1.1', 200, 'OK')
      response.stubs(:body).returns body

      subject.stubs(:http_get).returns response

      result = subject.find(request)
      result.should be_a(Puppet::Node::Facts)
      result.name.should == 'some_node'
      result.values.should include(facts)
    end

    it "should return nil if no facts are found" do
      response = Net::HTTPNotFound.new('1.1', 404, 'Not Found')

      subject.stubs(:http_get).returns response

      subject.find(request).should be_nil
    end

    it "should fail if an HTTP error code is returned" do
      response = Net::HTTPForbidden.new('1.1', 403, "Forbidden")
      response.stubs(:body).returns ''

      subject.stubs(:http_get).returns response

      expect {
        subject.find(request)
      }.to raise_error Puppet::Error, /\[403 Forbidden\]/
    end

    it "should fail if an error occurs" do
      subject.stubs(:http_get).raises Puppet::Error, "Everything is terrible!"

      expect {
        subject.find(request)
      }.to raise_error Puppet::Error, /Everything is terrible!/
    end
  end

  describe "#search" do
    let(:request) { Puppet::Node::Facts.indirection.request(:search, 'facts', @query) }
    let(:response) { Net::HTTPOK.new('1.1', 200, 'OK') }

    it "should return the nodes from the response" do
      @query = {
        'facts.kernel.eq' => 'Linux',
      }

      response.stubs(:body).returns '["foo", "bar", "baz"]'
      subject.stubs(:http_get).returns response

      subject.search(request).should == ['foo', 'bar', 'baz']
    end

    it "should only allow searches against facts" do
      @query = {
        'facts.kernel.eq' => 'Linux',
        'wrong.kernel.eq' => 'Linux',
      }

      expect do
        subject.search(request)
      end.to raise_error(Puppet::Error, /Fact search against keys of type 'wrong' is unsupported/)
    end

    it "should add a filter against only active nodes" do
      @query = {
        'facts.kernel.eq' => 'Linux',
      }

      query = CGI.escape(["and", ["=", ["node", "active"], true],
                                 ["=", ["fact", "kernel"], "Linux"]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url.should == "/nodes?query=#{query}"
      end.returns response

      subject.search(request)
    end

    it "should combine multiple terms with 'and'" do
      @query = {
        'facts.kernel.eq' => 'Linux',
        'facts.uptime.eq' => '10 days',
      }

      query = CGI.escape(["and", ["=", ["node", "active"], true],
                                 ["=", ["fact", "kernel"], "Linux"],
                                 ["=", ["fact", "uptime"], "10 days"]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url.should == "/nodes?query=#{query}"
      end.returns response

      subject.search(request)
    end

    it "should add 'not' to a != query" do
      @query = {
        'facts.kernel.ne' => 'Linux',
      }

      query = CGI.escape(["and", ["=", ["node", "active"], true],
                                 ["not", ["=", ["fact", "kernel"], "Linux"]]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url.should == "/nodes?query=#{query}"
      end.returns response

      subject.search(request)
    end

    it "should default the operator to = if one is not specified" do
      @query = {
        'facts.kernel' => 'Linux',
      }

      query = CGI.escape(["and", ["=", ["node", "active"], true],
                                 ["=", ["fact", "kernel"], "Linux"]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url.should == "/nodes?query=#{query}"
      end.returns response

      subject.search(request)
    end

    {
      'gt' => '>',
      'lt' => '<',
      'ge' => '>=',
      'le' => '<='
    }.each do |name, operator|
      it "should map '#{name}' to #{operator}" do
        @query = {
          "facts.kernel.#{name}" => 'Linux',
        }

        query = CGI.escape(["and", ["=", ["node", "active"], true],
                                   [operator, ["fact", "kernel"], "Linux"]].to_pson)

        response.stubs(:body).returns '[]'

        subject.expects(:http_get).with do |_,url,_|
          url.should == "/nodes?query=#{query}"
        end.returns response

        subject.search(request)
      end
    end

    it "should raise an error if a failure occurs" do
      response = Net::HTTPBadRequest.new('1.1', 400, 'Bad Request')
      response.stubs(:body).returns 'Something bad happened!'

      subject.stubs(:http_get).returns response

      expect do
        subject.search(request)
      end.to raise_error(Puppet::Error, /Could not perform inventory search from PuppetDB at localhost:0: \[400 Bad Request\] Something bad happened!/)
    end
  end
end
