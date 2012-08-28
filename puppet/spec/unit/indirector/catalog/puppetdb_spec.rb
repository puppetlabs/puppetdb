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

    describe "#stringify_titles" do
      it "should make all resource titles strings if they aren't" do
        Puppet[:code] = <<-MANIFEST
          $foo = true
          notify { $foo: }
        MANIFEST

        hash = catalog.to_pson_data_hash['data']
        result = subject.stringify_titles(hash)

        result['resources'].should be_any { |res|
          res['type'] == 'Notify' and res['title'] == 'true'
        }
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

      context "with resource types that provide #title_patterns" do
        context "if #title_patterns munges the title to set the namevar" do
          it "should add namevar to aliases if it's not already present" do
            # So, what we are testing here is the case where the resource type
            #  defines one or more title_patterns, which, when used to set
            #  the value of the namevar, may munge the value via regex
            #  awesomeness.  'File' is an example of such a resource, as
            #  it will strip trailing slashes from the title to set the
            #  :path parameter, if :path is not specified.
            #
            # In a case like this it is important that the munged value of
            #  the namevar be set as an alias, so that catalog dependencies
            #  can be resolved properly.

            # To test this, first we create a File resource whose title contains
            #  a trailing slash.
            file_resource = Puppet::Resource.new(:file, '/tmp/foo/')

            # I find it fairly well revolting that we can hack stuff into
            #  the compiler via this global :code variable.  It doesn't seem
            #  like it should be hard to provide a more explicit and sensible
            #  way to accomplish this...
            Puppet[:code] = file_resource.to_manifest

            hash = subject.add_parameters_if_missing(catalog.to_pson_data_hash['data'])
            result = subject.add_namevar_aliases(hash, catalog)

            resource = result['resources'].find do |res|
              res['type'] == 'File' and res['title'] == '/tmp/foo/'
            end

            # Now we need to check to make sure that there is an alias without
            #  the trailing slash.  This test relies on the secret knowledge
            #  that the File resource has a title_pattern that munges the
            #  namevar (in this case, removes trailing slashes), but hopefully
            #  this test should cover other resource types that fall into
            #  this category as well.
            resource.should_not be_nil
            resource['parameters']['alias'].should_not be_nil
            resource['parameters']['alias'].should include('/tmp/foo')
          end
        end
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

      describe "for resources with composite namevars" do
        let(:resource) do
          r = Puppet::Resource.new(:notify, 'yo matey')
          r.stubs(:key_attributes).returns [:name, :message]
          r
        end

        it "should not create aliases" do
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          result = subject.add_namevar_aliases(hash, catalog)

          resource = result['resources'].find do |res|
            res['type'] == 'Notify' and res['title'] == 'yo matey'
          end

          resource.should_not be_nil
          resource['parameters']['alias'].should be_nil
        end
      end

      describe "for non-isomorphic resources" do
        let(:resource) do
          Puppet::Resource.new(:exec, 'an_exec', :parameters => {:command => '/bin/true'})
        end

        it "should not create aliases" do
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          result = subject.add_namevar_aliases(hash, catalog)

          resource = result['resources'].find do |res|
            res['type'] == 'Exec' and res['title'] == 'an_exec'
          end

          resource.should_not be_nil
          resource['parameters']['alias'].should be_nil
        end
      end
    end

    describe "#sort_unordered_metaparams" do
      let(:resource) do
        Puppet::Resource.new(:exec, 'an_exec', :parameters => {:command => '/bin/true',
                                                               :path    => ['/foo/goo', '/foo/bar'],
                                                               :audit   => 'path',
                                                               :tag     => ['c', 'b', 'a']})
      end

      it "should leave ordered/singleton metaparams (and vanilla params) alone" do
        hash = subject.add_parameters_if_missing(catalog_data_hash)
        result = subject.sort_unordered_metaparams(hash)

        resource = result['resources'].find do |res|
          res['type'] == 'Exec' and res['title'] == 'an_exec'
        end

        resource.should_not be_nil
        resource['parameters'][:command].should == '/bin/true'
        resource['parameters'][:path].should == ['/foo/goo', '/foo/bar']
        resource['parameters'][:audit].should == 'path'
      end

      it "should sort unordered metaparams with array values" do
        hash = subject.add_parameters_if_missing(catalog_data_hash)
        result = subject.sort_unordered_metaparams(hash)

        resource = result['resources'].find do |res|
          res['type'] == 'Exec' and res['title'] == 'an_exec'
        end

        resource.should_not be_nil
        resource['parameters'][:audit].should == 'path'
        resource['parameters'][:tag].should == ['a', 'b', 'c']
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
        }.to raise_error("Can't synthesize edge: Notify\[anyone\] -required-by- Notify\[non-existent\] (param require)")
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
