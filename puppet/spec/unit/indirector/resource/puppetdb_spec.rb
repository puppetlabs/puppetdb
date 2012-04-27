#!/usr/bin/env rspec
require 'spec_helper'

require 'puppet/indirector/resource/puppetdb'

describe Puppet::Resource::Puppetdb do
  before :each do
    Puppet::Util::Puppetdb.stubs(:load_puppetdb_config).returns ['localhost', 0]
  end

  describe "#search" do
    let(:host) { 'default.local' }

    def search(type)
      scope = Puppet::Parser::Scope.new
      args = { :host => host, :filter => nil, :scope => scope }
      subject.search(Puppet::Resource.indirection.request(:search, type, args))
    end

    it "should return an empty array if no resources match" do
      response = Net::HTTPOK.new('1.1', 200, 'OK')
      response.stubs(:body).returns '[]'
      subject.stubs(:http_get).returns response
      search("exec").should == []
    end

    it "should fail it can't connect to the PuppetDB server" do
      expect { search("user") }.to raise_error(Puppet::Error, /Could not retrieve resources/)
    end

    describe "with a matching resource" do
      def make_resource_hash(name, exported=true)
        metadata = { :sourcefile => '/etc/puppet/manifests/site.pp',
                     :sourceline => 10,
                     :exported   => exported,
                     :hash       => 'foobarbaz', }

        res = Puppet::Type.type(:file).new(:title => File.expand_path(name),
                                           :ensure => :present,
                                           :mode => 0777)
        params = res.to_hash
        res_hash = metadata.merge(:type => res.type, :title => res.title)
        res_hash.merge(:parameters => params)
      end

      before :each do
        body = [make_resource_hash('foo'), make_resource_hash('bar')].to_pson
        query = ['and',
                  ['=', 'type', 'File'],
                  ['=', 'exported', true],
                  ['=', ['node', 'active'], true],
                  ['not', ['=', ['node', 'name'], host]]]

        response = Net::HTTPOK.new('1.1', 200, 'OK')
        response.stubs(:body).returns body

        Net::HTTP.any_instance.stubs(:get).with do |uri, headers|
          path, query_string = uri.split('?query=')
          path == '/resources' and PSON.load(query_string) == query
        end.returns response
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
  end

  describe "#validate_filter" do
    it "should be valid if there is no filter" do
      subject.validate_filter(nil).should == true
    end

    # Assert that this is a case-insensitive rule, too.
    %w{and or AND OR And Or anD oR < > <= >= <>}.each do |op|
      it "should fail if the filter uses a #{op.inspect} operator" do
        filter = [%w{tag == foo}, op, %w{title == bar}]
        expect { subject.validate_filter(filter) }.to raise_error Puppet::Error, /not supported/
      end
    end

    %w{= == !=}.each do |op|
      it "should not fail if the filter uses the #{op.inspect} operator" do
        filter = [%w{tag == foo}, op, %w{title == bar}]
        subject.validate_filter(filter).should == true
      end
    end
  end

  describe "#build_filter_expression" do
    it "should return nil if there is no filter" do
      subject.build_filter_expression(nil).should == nil
    end

    it "should return an equal query if the operator is '='" do
      subject.build_filter_expression(['param','=','value']).should == ['=',['parameter','param'],'value']
    end

    it "should return a not-equal query if the operator is '!='" do
      subject.build_filter_expression(['param','!=','value']).should == ['not', ['=',['parameter','param'],'value']]
    end
  end

  describe "#headers" do
    it "should accept the correct mime type" do
      subject.headers['Accept'].should == 'application/json'
    end
  end
end

