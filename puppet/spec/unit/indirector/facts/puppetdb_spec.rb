#!/usr/bin/env rspec
require 'spec_helper'

require 'puppet/util/feature'
require 'puppet/http/errors'
require 'puppet/indirector/facts/puppetdb'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'
require 'json'
require 'date'
require 'time'

describe Puppet::Node::Facts::Puppetdb do

  CommandReplaceFacts = Puppet::Util::Puppetdb::CommandNames::CommandReplaceFacts

  let(:http) { stub 'http' }
  let(:url) { "mock url" }
  let(:nethttpok) { Net::HTTPOK.new('1.1', 200, 'OK') }
  let(:responseok) { create_http_response(url, nethttpok) }

  before :each do
    Puppet::Util::Puppetdb.config.stubs(:server_urls).returns [URI("https://localhost:8282")]
    Puppet::Node::Facts.indirection.stubs(:terminus).returns(subject)
    Puppet::HTTP::Client.stubs(:new).returns(http)
    create_environmentdir("my_environment")
  end

  describe "#save" do
    let(:facts)    { Puppet::Node::Facts.new('foo') }
    let(:options) {{
      :environment => "my_environment",
    }}

    before :each do
      responseok.stubs(:body).returns '{"uuid": "a UUID"}'
    end

    def save
      subject.save(Puppet::Node::Facts.indirection.request(:save, facts.name, facts, options))
    end

    it "should POST the trusted data we tell it to" do

      trusted_data = {"foo" => "foobar", "certname" => "testing_posting"}
      subject.stubs(:get_trusted_info).returns trusted_data
      Puppet[:node_name_value] = "mom"

      payload = {
        "certname" => facts.name,
        "values" => facts.values.merge({"trusted" => trusted_data}),
        "environment" => "my_environment",
        "producer" => "mom"
      }

      http.expects(:post).with do |uri, body, headers|
        assert_command_req(payload, body)
      end.returns responseok

      save
    end


    it "should retain integer type when submitting" do
      facts.values['something'] = 100

      sent_payload = nil
      http.expects(:post).with do |uri, body, headers|
        sent_payload = body
      end.returns responseok

      save

      message = JSON.parse(sent_payload)

      # We shouldn't modify the original instance
      facts.values['something'].should == 100
      message['values']['something'].should == 100
    end

    it "should transform the package inventory fact when submitting" do
      fact_tuple = ['openssl', '1.0.2g-1ubuntu4.6', 'apt']
      inventory_fact_value = { 'packages' => [fact_tuple] }

      facts.values['_puppet_inventory_1'] = inventory_fact_value

      sent_payload = nil
      http.expects(:post).with do |uri, body, headers|
        sent_payload = body
      end.returns responseok
      save
      message = JSON.parse(sent_payload)

      # We shouldn't modify the original instance
      facts.values['_puppet_inventory_1'].should == inventory_fact_value

      message['values']['_puppet_inventory_1'].should be_nil
      message['package_inventory'].should == [fact_tuple]
    end

    it "shouldn't crash with a malformed inventory fact" do
      facts.values['_puppet_inventory_1'] = ['foo', 'bar']

      sent_payload = nil
      http.expects(:post).with do |uri, body, headers|
        sent_payload = body
      end.returns responseok
      save
    end
  end

  describe "#get_trusted_info" do
    it 'should return trusted data' do
      node = Puppet::Node.new('my_certname')
      trusted = subject.get_trusted_info(node)
      # External key added by PUP-9994, Puppet 6.11.0
      if trusted.has_key?('external')
        expect(trusted).to eq({'authenticated'=>'local', 'certname'=>'testing',
                               'extensions'=>{}, 'external'=>{"trusted_testhelper"=>true}, 'hostname'=>'testing', 'domain'=>nil})
      # Extra keys domain & hostname introduced by PUP-5097, Puppet 4.3.0
      elsif trusted.has_key?("domain")
        expect(trusted).to eq({'authenticated'=>'local', 'certname'=>'testing',
                               'extensions'=>{}, 'hostname'=>'testing', 'domain'=>nil})
      else
        # Puppet 4.2.x and older
        expect(trusted).to eq({'authenticated'=>'local', 'certname'=>'testing', 'extensions'=>{}})
      end
    end

    it 'should return trusted data when falling back to the node' do
      # This removes :trusted_information from the global context, triggering our fallback code.
      if Puppet.methods.include? :rollback_context
        Puppet.rollback_context('initial testing state')
      else
        Puppet.pop_context # puppet 3.5.1
      end

      node = Puppet::Node.new('my_certname', :parameters => {'clientcert' => 'trusted_certname'})
      trusted = subject.get_trusted_info(node)

      # External key added by PUP-9994, Puppet 6.11.0
      if trusted.has_key?('external')
        expect(trusted).to eq({'authenticated'=>'local', 'certname'=>'trusted_certname',
                               'extensions'=>{}, 'external'=>{}, 'hostname'=>'trusted_certname', 'domain'=>nil})
      # Extra keys domainname & hostname introduced by PUP-5097, Puppet 4.3.0
      elsif trusted.has_key?("domain")
        expect(trusted).to eq({'authenticated'=>'local', 'certname'=>'trusted_certname',
                               'extensions'=>{}, 'hostname'=>'trusted_certname', 'domain'=>nil})
      else
        # Puppet 4.2.x and older
        expect(trusted).to eq({'authenticated'=>'local', 'certname'=>'trusted_certname', 'extensions'=>{}})
      end

      # Put the context back the way the test harness expects
      Puppet.push_context({}, 'context to make the tests happy')
      if Puppet.methods.include? :mark_context
        Puppet.mark_context('initial testing state')
      end
    end
  end

  describe "#find" do
    def find_facts()
      Puppet::Node::Facts.indirection.find('some_node')
    end

    let(:options) { {:metric_id => [:puppetdb, :facts, :find],
                     :ssl_context => nil} }

    it "should return the facts if they're found" do
      body = [{"certname" => "some_node", "environment" => "production", "name" => "a", "value" => "1"},
              {"certname" => "some_node", "environment" => "production", "name" => "b", "value" => "2"}].to_json

      responseok.stubs(:body).returns body

      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes/some_node/facts" == uri.path &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.returns responseok


      result = find_facts
      result.should be_a(Puppet::Node::Facts)
      result.name.should == 'some_node'
      result.values.should include('a' => '1', 'b' => '2')
    end

    it "should return nil if no facts are found" do
      body = {"error" => "No information known about factset some_node"}.to_json

      notfound = Net::HTTPNotFound.new('1.1', 404, 'NotFound')
      notfound.stubs(:body).returns body

      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes/some_node/facts" == uri.path &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.raises Puppet::HTTP::ResponseError, create_http_response(url, notfound)

      find_facts.should be_nil
    end

    it "should fail if an HTTP error code is returned" do
      response = Net::HTTPForbidden.new('1.1', 403, "Forbidden")
      response.stubs(:body).returns ''

      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes/some_node/facts" == uri.path &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.raises Puppet::HTTP::ResponseError, create_http_response(url, response)

      expect {
        find_facts
      }.to raise_error Puppet::Error, /\[403 Forbidden\]/
    end

    it "should fail if an error occurs" do
      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes/some_node/facts" == uri.path &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.raises Puppet::Error, "Everything is terrible!"

      expect {
        find_facts
      }.to raise_error Puppet::Error, /Everything is terrible!/
    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      nethttp = Net::HTTPOK.new('1.1', 200, 'OK')
      nethttp['x-deprecation'] = "This is deprecated!"

      response = create_http_response(url, nethttp)

      body = [].to_json

      response.stubs(:body).returns body

      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes/some_node/facts" == uri.path &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.returns response

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
    let(:options) { {:metric_id => [:puppetdb, :facts, :search]} }

    it "should return the nodes from the response" do
      args = {
        'facts.kernel.eq' => 'Linux',
      }

      responseok.stubs(:body).returns '["foo", "bar", "baz"]'
      responseok.stubs(:body).returns '[{"name": "foo", "deactivated": null, "expired": null, "catalog_timestamp": null, "facts_timestamp": null, "report_timestamp": null},
                                      {"name": "bar", "deactivated": null, "expired": null, "catalog_timestamp": null, "facts_timestamp": null, "report_timestamp": null},
                                      {"name": "baz", "deactivated": null, "expired": null, "catalog_timestamp": null, "facts_timestamp": null, "report_timestamp": null}]'

      query = CGI.escape("[\"and\",[\"=\",[\"fact\",\"kernel\"],\"Linux\"]]")
      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes?query=#{query}" == "#{uri.path}?#{uri.query}" &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.returns responseok

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
                                 ["=", ["fact", "uptime"], "10 days"]].to_json)

      responseok.stubs(:body).returns '[]'

      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes?query=#{query}" == "#{uri.path}?#{uri.query}" &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.returns responseok

      search_facts(args)
    end

    it "should add 'not' to a != query" do
      args = {
        'facts.kernel.ne' => 'Linux',
      }

      query = CGI.escape(["and", ["not", ["=", ["fact", "kernel"], "Linux"]]].to_json)

      responseok.stubs(:body).returns '[]'

      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes?query=#{query}" == "#{uri.path}?#{uri.query}" &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.returns responseok

      search_facts(args)
    end

    it "should default the operator to = if one is not specified" do
      args = {
        'facts.kernel' => 'Linux',
      }

      query = CGI.escape(["and", ["=", ["fact", "kernel"], "Linux"]].to_json)

      responseok.stubs(:body).returns '[]'

      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes?query=#{query}" == "#{uri.path}?#{uri.query}" &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.returns responseok

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

        query = CGI.escape(["and", [operator, ["fact", "kernel"], "Linux"]].to_json)

        responseok.stubs(:body).returns '[]'

        http.stubs(:get).with do |uri, opts|
          "/pdb/query/v4/nodes?query=#{query}" == "#{uri.path}?#{uri.query}" &&
            subject.headers == opts[:headers] &&
            options == opts[:options]
        end.returns responseok

        search_facts(args)
      end
    end

    it "should raise an error if a failure occurs" do
      response = Net::HTTPBadRequest.new('1.1', 400, 'Bad Request')
      response.stubs(:body).returns 'Something bad happened!'

      query = CGI.escape(["and"].to_json)
      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes?query=#{query}" == "#{uri.path}?#{uri.query}" &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.raises Puppet::HTTP::ResponseError, create_http_response(url, response)

      expect do
        search_facts(nil)
      end.to raise_error(Puppet::Error, /\[400 Bad Request\] Something bad happened!/)

    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      nethttpok['x-deprecation'] = "This is deprecated!"
      responseok.stubs(:body).returns '[]'

      query = CGI.escape(["and"].to_json)
      http.stubs(:get).with do |uri, opts|
        "/pdb/query/v4/nodes?query=#{query}" == "#{uri.path}?#{uri.query}" &&
          subject.headers == opts[:headers] &&
          options == opts[:options]
      end.returns responseok
      http.stubs(:get).with("/pdb/query/v4/nodes?query=#{query}",  subject.headers, options).returns(responseok)

      Puppet.expects(:deprecation_warning).with do |msg|
        msg =~ /This is deprecated!/
      end

      search_facts(nil)
    end
  end
end
