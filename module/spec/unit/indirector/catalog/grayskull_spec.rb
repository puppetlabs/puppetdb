#!/usr/bin/env rspec
require 'spec_helper'

require 'puppet/indirector/catalog/grayskull'

describe Puppet::Resource::Catalog::Grayskull do
  describe "#save" do
    let(:catalog) do
      cat = Puppet::Resource::Catalog.new('foo')
      cat.add_resource(Puppet::Resource.new(:file, 'my_file'))
      cat
    end

    def save
      subject.save(Puppet::Resource::Catalog.indirection.request(:save, catalog.name, catalog))
    end

    it "should POST the catalog command as a URL-encoded PSON string" do
      payload = {
        :command => "replace catalog",
        :version => 1,
        :payload => catalog.to_pson,
      }.to_pson

      subject.expects(:http_post).with do |request,uri,body,headers|
        body =~ /^payload=(.+)/
        @sent_payload = $1
      end

      save

      URI.decode(@sent_payload).should == payload
    end
  end
end
