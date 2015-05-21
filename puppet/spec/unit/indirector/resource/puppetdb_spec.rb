#!/usr/bin/env rspec
require 'spec_helper'

require 'puppet/indirector/resource/puppetdb'
require 'json'

describe Puppet::Resource::Puppetdb do
  before :each do
    Puppet::Util::Puppetdb.stubs(:server).returns 'localhost'
    Puppet::Util::Puppetdb.stubs(:port).returns 0
    Puppet::Resource.indirection.stubs(:terminus).returns(subject)
  end

  describe "#search" do
    let(:host) { 'default.local' }

    def search(type)
      # The API for creating scope objects is different between Puppet 2.7 and
      # 3.0. The scope here isn't really used for anything relevant, so it's
      # easiest to make it a stub to run against both versions of Puppet.
      scope = stub('scope', :source => nil)
      args = { :host => host, :filter => nil, :scope => scope }
      Puppet::Resource.indirection.search(type, args)
    end

    it "should return an empty array if no resources match" do
      response = Net::HTTPOK.new('1.1', 200, 'OK')
      response.stubs(:body).returns '[]'

      query = CGI.escape(["and", ["=", "type", "exec"], ["=", "exported", true], ["not", ["=", "certname", "default.local"]]].to_json)
      http = stub 'http'
      Puppet::Network::HttpPool.stubs(:http_instance).returns(http)
      http.stubs(:get).with("/pdb/query/v4/resources?query=#{query}",  subject.headers).returns response

      search("exec").should == []
    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      response = Net::HTTPOK.new('1.1', 200, 'OK')
      response['x-deprecation'] = "Deprecated, yo."

      response.stubs(:body).returns '[]'

      query = CGI.escape(["and", ["=", "type", "exec"], ["=", "exported", true], ["not", ["=", "certname", "default.local"]]].to_json)
      http = stub 'http'
      Puppet::Network::HttpPool.stubs(:http_instance).returns(http)
      http.stubs(:get).with("/pdb/query/v4/resources?query=#{query}",  subject.headers).returns response

      Puppet.expects(:deprecation_warning).with do |msg|
        msg =~ /Deprecated, yo\./
      end

      search("exec")
    end

    it "should fail it can't connect to the PuppetDB server" do
      expect { search("user") }.to raise_error(Puppet::Error, /Could not retrieve resources/)
    end

    describe "with a matching resource" do

      let (:query) {
          ['and',
           ['=', 'type', 'File'],
           ['=', 'exported', true],
           ['not', ['=', 'certname', host]]]
      }

      def make_resource_hash(name, certname="localhost", exported=true)
        metadata = { :file => '/etc/puppet/manifests/site.pp',
                     :line => 10,
                     :exported   => exported,
                     :certname   => certname,
                     :hash       => 'foobarbaz', }

        res = Puppet::Type.type(:file).new(:title => File.expand_path(name),
                                           :ensure => :present,
                                           :mode => "0777")
        params = res.to_hash
        res_hash = metadata.merge(:type => res.type, :title => res.title)
        res_hash.merge(:parameters => params)
      end

      def stub_response(resource_hashes)
        body = resource_hashes.to_json

        response = Net::HTTPOK.new('1.1', 200, 'OK')
        response.stubs(:body).returns body

        http = stub 'http'
        Puppet::Network::HttpPool.stubs(:http_instance).returns(http)
        http.stubs(:get).with("/pdb/query/v4/resources?query=#{CGI.escape(query.to_json)}",  subject.headers).returns response
      end

      context "with resources from a single host" do
        before :each do
          stub_response([make_resource_hash('foo'), make_resource_hash('bar')])
        end


        it "should return a list of parser resources if any resources are found" do
          found = search('File')
          found.length.should == 2
          found.each do |item|
            item.should be_a(Puppet::Parser::Resource)
            item.type.should == 'File'
            item[:ensure].should == 'present'
            item[:mode].should == '777'
          end
        end


        it "should not filter resources that have been found before" do
          search('File').should == search('File')
        end
      end

      context "with resources from multiple hosts" do
        before :each do
          stub_response([make_resource_hash('foo', 'localhost'), make_resource_hash('foo', 'remotehost')])
        end

        it "should supply unique collector_id vals for resources collected from different hosts" do
          found = search('File')
          found.length.should == 2
          found[0].collector_id.should_not == found[1].collector_id
        end
      end


    end
  end

  describe "#build_expression" do
    it "should return nil if there is no filter" do
      subject.build_expression(nil).should == nil
    end

    it "should fail if the filter uses an illegal operator" do
      expect do
        subject.build_expression(['foo', 'xor', 'bar'])
      end.to raise_error(Puppet::Error, /not supported/)
    end

    it "should return an equal query if the operator is '='" do
      subject.build_expression(['param','==','value']).should == ['=',['parameter','param'],'value']
    end

    it "should return a not-equal query if the operator is '!='" do
      subject.build_expression(['param','!=','value']).should == ['not', ['=', ['parameter','param'],'value']]
    end

    it "should handle title correctly" do
      subject.build_expression(['title','==','value']).should == ['=', 'title', 'value']
    end

    it "should preserve the case of title queries" do
      subject.build_expression(['title','==','VALUE']).should == ['=', 'title', 'VALUE']
    end

    it "should handle tag correctly" do
      subject.build_expression(['tag','==','value']).should == ['=', 'tag', 'value']
    end

    it "should generate lowercase tag queries for case-insensitivity" do
      subject.build_expression(['tag','==','VALUE']).should == ['=', 'tag', 'value']
    end

    it "should conjoin 'and' queries with 'and'" do
      query = [['tag', '==', 'one'], 'and', ['tag', '==', 'two']]
      subject.build_expression(query).should == ['and',
                                                  ['=', 'tag', 'one'],
                                                  ['=', 'tag', 'two']]
    end

    it "should conjoin 'or' queries with 'or'" do
      query = [['tag', '==', 'one'], 'or', ['tag', '==', 'two']]
      subject.build_expression(query).should == ['or',
                                                  ['=', 'tag', 'one'],
                                                  ['=', 'tag', 'two']]
    end

    it "should construct complex, nested queries" do
      query = [[['tag', '==', 'one'], 'and', ['tag', '==', 'two']], 'or', ['tag', '!=', 'three']]
      subject.build_expression(query).should == ['or',
                                                  ['and',
                                                    ['=', 'tag', 'one'],
                                                    ['=', 'tag', 'two']],
                                                  ['not',
                                                   ['=', 'tag', 'three']]]
    end
  end

  describe "#headers" do
    it "should accept the correct mime type" do
      subject.headers['Accept'].should == 'application/json'
    end
  end
end
