#!/usr/bin/env rspec

require 'spec_helper'

require 'puppet/indirector/catalog/puppetdb'

describe Puppet::Resource::Catalog::Puppetdb do
  before :each do
    Puppet::Util::Puppetdb.stubs(:load_puppetdb_config).returns ['localhost', 0]
  end

  describe "#save" do
    let(:response) { Net::HTTPOK.new('1.1', 200, 'OK') }
    let(:catalog) do
      cat = Puppet::Resource::Catalog.new('foo')
      cat.add_resource(Puppet::Resource.new(:file, 'my_file'))
      cat
    end

    before :each do
      response.stubs(:body).returns '{"uuid": "a UUID"}'
    end

    def save
      subject.save(Puppet::Resource::Catalog.indirection.request(:save, catalog.name, catalog))
    end

    it "should POST the catalog command as a URL-encoded PSON string" do
      payload_str = subject.munge_catalog(catalog).to_pson
      payload = {
        :command => "replace catalog",
        :version => 1,
        :payload => payload_str,
      }.to_pson

      subject.expects(:http_post).with do |request,uri,body,headers|
        body =~ /payload=(.+)/
        @sent_payload = $1
      end.returns response

      save

      CGI.unescape(@sent_payload).should == payload
    end
  end

  describe "catalog transformation methods" do
    let(:catalog) { Puppet::Parser::Compiler.compile(Puppet::Node.new('node')) }
    let(:resource) { Puppet::Resource.new(:notify, 'anyone') }

    # This is a little abuse of the laziness of let. Set Puppet[:code], then
    # create the catalog based on that manifest simply by asking for it.
    def catalog_data_hash
      Puppet[:code] = resource.to_manifest
      catalog.to_pson_data_hash['data']
    end

    describe "#add_parameters_if_missing" do
      it "should create an empty parameters hash if none exists" do
        result = subject.add_parameters_if_missing(catalog_data_hash)

        result['resources'].each do |res|
          res['parameters'].should be_a(Hash)
        end
      end

      it "should leave an existing parameters hash alone" do
        msg = "with up so floating many bells down"
        resource[:message] = msg

        result = subject.add_parameters_if_missing(catalog_data_hash)
        resource = result['resources'].find do |res|
          res['type'] == 'Notify' and res['title'] == 'anyone'
        end

        resource.should_not be_nil
        resource['parameters'].should == {:message => msg}
      end
    end

    describe "#add_namevar_aliases" do
      it "should add namevar to aliases if it's not already present" do
        name = 'with a different name'
        resource[:name] = name

        hash = subject.add_parameters_if_missing(catalog_data_hash)
        result = subject.add_namevar_aliases(hash, catalog)

        resource = result['resources'].find do |res|
          res['type'] == 'Notify' and res['title'] == 'anyone'
        end

        resource.should_not be_nil
        resource['parameters']['alias'].should include(name)
      end

      it "should not create an alias parameter if the list would be empty" do
        hash = subject.add_parameters_if_missing(catalog_data_hash)
        result = subject.add_namevar_aliases(hash, catalog)

        resource = result['resources'].find do |res|
          res['type'] == 'Notify' and res['title'] == 'anyone'
        end

        resource.should_not be_nil
        resource['parameters']['alias'].should be_nil
      end
    end

    describe "#munge_edges" do
      it "should replace existing source/target refs with type/title hashes" do
        result = subject.munge_edges(catalog_data_hash)

        # Ensure we don't get a vacuous success from an empty list
        result['edges'].should_not be_empty

        result['edges'].each do |edge|
          edge['source'].should be_a(Hash)
          edge['source'].keys.should =~ ['type', 'title']
          edge['target'].should be_a(Hash)
          edge['target'].keys.should =~ ['type', 'title']
        end
      end

      it "should leave type/title hashes alone" do
        hash = catalog_data_hash

        edge = {'source' => {'type' => 'Notify', 'title' => 'bar'},
                'target' => {'type' => 'Notify', 'title' => 'baz'},
                'relationship' => 'notifies'}

        hash['edges'] << edge.dup

        result = subject.munge_edges(hash)
        result['edges'].should include(edge)
      end

      it "should set the edge relationship to contains if it doesn't have one" do
        result = subject.munge_edges(catalog_data_hash)
        result['edges'].each do |edge|
          edge['relationship'].should == 'contains'
        end
      end
    end

    describe "#synthesize_edges" do
      it "should add edges based on relationship metaparameters" do
        other_resource = Puppet::Resource.new(:notify, 'noone', :parameters => {:require => "Notify[anyone]"})
        Puppet[:code] = [resource, other_resource].map(&:to_manifest).join

        hash = catalog.to_pson_data_hash['data']
        subject.add_parameters_if_missing(hash)
        result = subject.synthesize_edges(hash)

        edge = {'source' => {'type' => 'Notify', 'title' => 'anyone'},
                'target' => {'type' => 'Notify', 'title' => 'noone'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      it "should properly add edges for defined types" do
        Puppet[:code] = <<-CODE
define foo::bar() {
  notify { $name: }
}
foo::bar { foo:
  require => Foo::Bar[bar],
}
foo::bar { bar: }
        CODE

        hash = catalog.to_pson_data_hash['data']
        subject.add_parameters_if_missing(hash)
        result = subject.synthesize_edges(hash)

        edge = {'source' => {'type' => 'Foo::Bar', 'title' => 'bar'},
                'target' => {'type' => 'Foo::Bar', 'title' => 'foo'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      it "should add edges even if the other end is an alias" do
        other_resource = Puppet::Resource.new(:notify, 'noone', :parameters => {:alias => 'another_thing'})
        resource[:require] = 'Notify[another_thing]'
        Puppet[:code] = [resource, other_resource].map(&:to_manifest).join

        hash = catalog.to_pson_data_hash['data']
        subject.add_parameters_if_missing(hash)
        subject.add_namevar_aliases(hash, catalog)
        result = subject.synthesize_edges(hash)

        edge = {'source' => {'type' => 'Notify', 'title' => 'noone'},
                'target' => {'type' => 'Notify', 'title' => 'anyone'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      it "should not add edges from exported resources" do
        other_resource = Puppet::Resource.new(:notify, 'noone')
        resource[:require] = 'Notify[noone]'
        Puppet[:code] = "@@#{resource.to_manifest}\n#{other_resource.to_manifest}"

        hash = catalog.to_pson_data_hash['data']
        subject.add_parameters_if_missing(hash)
        result = subject.synthesize_edges(hash)

        edge = {'source' => {'type' => 'Notify', 'title' => 'noone'},
                'target' => {'type' => 'Notify', 'title' => 'anyone'},
                'relationship' => 'required-by'}

        result['edges'].should_not include(edge)
      end

      it "should complain if a non-exported resource has a relationship with an exported resource" do
        other_resource = Puppet::Resource.new(:notify, 'noone')
        other_resource[:before] = "Notify[anyone]"
        Puppet[:code] = "@@#{resource.to_manifest}\n#{other_resource.to_manifest}"

        hash = catalog.to_pson_data_hash['data']
        subject.add_parameters_if_missing(hash)
        expect {
          subject.synthesize_edges(hash)
        }.to raise_error(/Can't create an edge between Notify\[noone\] and exported resource Notify\[anyone\]/)
      end

      it "should complain if a resource has a relationship with a non-existent resource" do
        resource[:require] = 'Notify[non-existent]'
        hash = subject.add_parameters_if_missing(catalog_data_hash)
        expect {
          subject.synthesize_edges(hash)
        }.to raise_error(/Can't find resource Notify\[non-existent\] for relationship/)
      end
    end

    describe "#munge_catalog" do
      it "should make an edge if the other end is referred to by its namevar" do
        other_resource = Puppet::Resource.new(:notify, 'noone', :parameters => {:name => 'completely_different'})
        resource[:require] = 'Notify[completely_different]'
        Puppet[:code] = [resource, other_resource].map(&:to_manifest).join

        result = subject.munge_catalog(catalog)

        edge = {'source' => {'type' => 'Notify', 'title' => 'noone'},
                'target' => {'type' => 'Notify', 'title' => 'anyone'},
                'relationship' => 'required-by'}

        result['data']['edges'].should include(edge)
      end
    end
  end
end
