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

  describe "#search" do
    let(:request) { Puppet::Node::Facts.indirection.request(:search, 'facts', @query) }
    let(:response) { Net::HTTPOK.new('1.1', 200, '') }

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

    it "should leave a single term alone" do
      @query = {
        'facts.kernel.eq' => 'Linux',
      }

      query = CGI.escape(["=", ["fact", "kernel"], "Linux"].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url == "/nodes?query=#{query}"
      end.returns response

      subject.search(request)
    end

    it "should combine multiple terms with 'and'" do
      @query = {
        'facts.kernel.eq' => 'Linux',
        'facts.uptime.eq' => '10 days',
      }

      query = CGI.escape(["and", ["=", ["fact", "kernel"], "Linux"],
                                 ["=", ["fact", "uptime"], "10 days"]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url == "/nodes?query=#{query}"
      end.returns response

      subject.search(request)
    end

    it "should add 'not' to a != query" do
      @query = {
        'facts.kernel.ne' => 'Linux',
      }

      query = CGI.escape(["not", ["=", ["fact", "kernel"], "Linux"]].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url == "/nodes?query=#{query}"
      end.returns response

      subject.search(request)
    end

    it "should default the operator to = if one is not specified" do
      @query = {
        'facts.kernel' => 'Linux',
      }

      query = CGI.escape(["=", ["fact", "kernel"], "Linux"].to_pson)

      response.stubs(:body).returns '[]'

      subject.expects(:http_get).with do |_,url,_|
        url == "/nodes?query=#{query}"
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

        query = CGI.escape([operator, ["fact", "kernel"], "Linux"].to_pson)

        response.stubs(:body).returns '[]'

        subject.expects(:http_get).with do |_,url,_|
          url == "/nodes?query=#{query}"
        end.returns response

        subject.search(request)
      end
    end

    it "should raise an error if a failure occurs" do
      response = Net::HTTPBadRequest.new('1.1', 400, '')
      response.stubs(:body).returns 'Something bad happened!'

      subject.stubs(:http_get).returns response

      expect do
        subject.search(request)
      end.to raise_error(Puppet::Error, /Could not perform inventory search: 400 Something bad happened!/)
    end
  end
end
