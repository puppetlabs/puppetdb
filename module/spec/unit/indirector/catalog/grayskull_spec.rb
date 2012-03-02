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
        body =~ /payload=(.+)/
        @sent_payload = $1
      end

      save

      CGI.unescape(@sent_payload).should == payload
    end

    describe "#munge_catalog" do
      it "should add namevar to aliases if it's not already present" do
        name = 'with a different name'
        notify = Puppet::Resource.new(:notify, 'notify_me', :parameters => {:name => name})

        catalog.add_resource(notify)

        hash = subject.munge_catalog(catalog)

        resource = hash['data']['resources'].find do |res|
          res['type'] == 'Notify' and res['title'] == 'notify_me'
        end

        resource.should_not be_nil
        resource['parameters']['alias'].should include(name)
      end
    end
  end
end
