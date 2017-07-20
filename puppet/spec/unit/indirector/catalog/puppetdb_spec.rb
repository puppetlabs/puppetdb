#!/usr/bin/env rspec

require 'spec_helper'

require 'puppet/indirector/catalog/puppetdb'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'
require 'json'
require 'puppet/resource/catalog'
require 'puppet/version'
require 'date'
require 'time'

describe Puppet::Resource::Catalog::Puppetdb do
  before :each do
    Puppet::Util::Puppetdb.stubs(:server_urls).returns 'https://localhost:0'
    create_environmentdir("my_environment")
  end

  describe "#save" do
    let(:response) { Net::HTTPOK.new('1.1', 200, 'OK') }
    let(:http)     { mock 'http' }
    let(:catalog) do
      cat = Puppet::Resource::Catalog.new('foo')
      cat.add_resource(Puppet::Resource.new(:file, 'my_file'))
      cat
    end
    let(:options) {{
      :transaction_uuid => 'abcdefg',
      :environment => 'my_environment'
    }}

    before :each do
      response.stubs(:body).returns '{"uuid": "a UUID"}'
      Puppet::Network::HttpPool.expects(:http_instance).returns http
    end

    def save
      subject.save(Puppet::Resource::Catalog.indirection.request(:save, catalog.name, catalog, options))
    end

    it "should POST the catalog command as a JSON string" do
      command_payload = subject.munge_catalog(catalog, Time.now.utc, options)
      http.expects(:post).with do |uri, body, headers|
        command_payload.delete("producer_timestamp")
        assert_command_req(command_payload, body)
      end.returns response

      save
    end

    it "should log a deprecation warning if one is returned from PuppetDB" do
      response['x-deprecation'] = 'A horrible deprecation warning!'

      Puppet.expects(:deprecation_warning).with do |msg|
        msg =~ /A horrible deprecation warning!/
      end

      http.stubs(:post).returns response

      save
    end
  end

  describe "catalog transformation methods" do
    let(:catalog) { Puppet::Parser::Compiler.compile(Puppet::Node.new('node')) }
    let(:resource) { Puppet::Resource.new(:notify, 'anyone') }

    # This is a little abuse of the laziness of let. Set Puppet[:code], then
    # create the catalog based on that manifest simply by asking for it.
    def catalog_data_hash
      Puppet[:code] = resource.to_manifest
      catalog.to_data_hash
    end

    describe "#add_transaction_uuid" do
      it "should add the given transaction uuid" do
        result = subject.add_transaction_uuid(catalog_data_hash, 'abc123')
        result['transaction_uuid'].should == 'abc123'
      end

      it "should add nil transaction uuid if none was given" do
        result = subject.add_transaction_uuid(catalog_data_hash, nil)
        result.has_key?('transaction_uuid').should be_truthy
        result['transaction_uuid'].should be_nil
      end
    end

    describe "#add_catalog_uuid_if_missing" do
        it "should not change the catalog_uuid if one was present" do
          # Puppet v4.3.2 doesn't actually have catalog_uuid, it will be 4.3.3
          # which has the change but until they bump the development version we
          # need this extra `and` clause
          if (Gem::Version.new(Puppet.version) >= Gem::Version.new('4.3.2') and
              catalog_data_hash.has_key?('catalog_uuid')) then
            result = subject.add_catalog_uuid_if_missing(catalog_data_hash, 'abc123')
            result['catalog_uuid'].should_not == 'abc123'
          else
            result = subject.add_catalog_uuid_if_missing(catalog_data_hash, 'abc123')
            result['catalog_uuid'].should == 'abc123'
          end
        end
    end

    describe "#add_code_id_if_missing" do
      it "should add the code_id key" do
        result = subject.add_code_id_if_missing(catalog_data_hash)
        result.has_key?('code_id').should be_truthy
        result['code_id'].should be_nil
      end
    end

    describe "#add_job_id_if_missing" do
      it "should add the job_id key" do
        result = subject.add_job_id_if_missing(catalog_data_hash, nil)
        result.has_key?('job_id').should be_truthy
        result['job_id'].should be_nil
      end
    end

    describe "#add_producer" do
      it "should add the producer key" do
        result = subject.add_producer(catalog_data_hash, "foo")
        result.has_key?('producer').should be_truthy
        result['producer'].should == "foo"
      end
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
        resource['message'] = msg

        result = subject.add_parameters_if_missing(catalog_data_hash)
        resource = result['resources'].find do |res|
          res['type'] == 'Notify' and res['title'] == 'anyone'
        end

        resource.should_not be_nil
        resource['parameters'].should == {'message' => msg}
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

            hash = subject.add_parameters_if_missing(catalog.to_data_hash)
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
          Puppet::Resource.new(:exec, 'an_exec', :parameters => {:command => '/bin/true', :alias => 'something awesome'})
        end

        it "should not add a namevar alias" do
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          result = subject.add_namevar_aliases(hash, catalog)

          resource = result['resources'].find do |res|
            res['type'] == 'Exec' and res['title'] == 'an_exec'
          end

          resource.should_not be_nil
          resource['parameters']['alias'].should == ['something awesome']
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
        resource['parameters']['command'].should == '/bin/true'
        resource['parameters']['path'].should == ['/foo/goo', '/foo/bar']
        resource['parameters']['audit'].should == 'path'
      end

      it "should sort unordered metaparams with array values" do
        hash = subject.add_parameters_if_missing(catalog_data_hash)
        result = subject.sort_unordered_metaparams(hash)

        resource = result['resources'].find do |res|
          res['type'] == 'Exec' and res['title'] == 'an_exec'
        end

        resource.should_not be_nil
        resource['parameters']['audit'].should == 'path'
        resource['parameters']['tag'].should == ['a', 'b', 'c']
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

        hash = catalog.to_data_hash
        subject.add_parameters_if_missing(hash)
        result = subject.synthesize_edges(hash, catalog)

        edge = {'source' => {'type' => 'Notify', 'title' => 'anyone'},
                'target' => {'type' => 'Notify', 'title' => 'noone'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      it "should add edges from relationship arrows" do
        other_resource = Puppet::Resource.new(:notify, 'noone')
        Puppet[:code] = [resource, other_resource].map(&:to_manifest).join
        Puppet[:code] << "Notify[anyone] -> Notify[noone]"

        hash = catalog.to_data_hash
        subject.add_parameters_if_missing(hash)
        result = subject.synthesize_edges(hash, catalog)

        edge = {'source' => {'type' => 'Notify', 'title' => 'anyone'},
                'target' => {'type' => 'Notify', 'title' => 'noone'},
                'relationship' => 'before'}

        result['edges'].should include(edge)
      end

      it "should allow resources with newlines" do
        Puppet[:code] = <<-MANIFEST
        exec{"foo\nbar": }
        exec{'/bin/true': subscribe => Exec["foo\nbar"]}
        MANIFEST

        expect { subject.munge_catalog(catalog, Time.now.utc) }.not_to raise_error
      end

      describe "exported resources" do
        before :each do
          Puppet[:storeconfigs] = true
          Puppet[:storeconfigs_backend] = 'puppetdb'
          Puppet::Resource.indirection.stubs(:search).returns []
        end

        let(:edge) do
          {
            'source' => {'type' => 'Notify', 'title' => 'source'},
            'target' => {'type' => 'Notify', 'title' => 'target'},
            'relationship' => 'before'
          }
        end

        it "should add edges which refer to collected exported resources" do
          Puppet[:code] = <<-MANIFEST
          notify { source:
            before => Notify[target],
          }

          @@notify { target: }

          Notify <<| |>>
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should include(edge)
        end

        it "should add edges defined on collected exported resources" do
          Puppet[:code] = <<-MANIFEST
          @@notify { source:
            before => Notify[target],
          }

          notify { target: }

          Notify <<| |>>
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should include(edge)
        end

        it "should fail if an edge refers to an uncollected exported resource" do
          Puppet[:code] = <<-MANIFEST
          notify { source:
            before => Notify[target],
          }

          @@notify { target: }
          MANIFEST

          expect do
            subject.munge_catalog(catalog, Time.now.utc)
          end.to raise_error(Puppet::Error, "Invalid relationship: Notify[source] { before => Notify[target] }, because Notify[target] is exported but not collected")
        end

        it "should not add edges defined on an uncollected exported resource" do
          Puppet[:code] = <<-MANIFEST
          @@notify { source:
            before => Notify[target],
          }

          notify { target: }
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should_not include(edge)
        end
      end

      describe "virtual resources" do
        let(:edge) do
          {
            'source' => {'type' => 'Notify', 'title' => 'source'},
            'target' => {'type' => 'Notify', 'title' => 'target'},
            'relationship' => 'before'
          }
        end

        it "should add edges which refer to collected virtual resources" do
          Puppet[:code] = <<-MANIFEST
          notify { source:
            before => Notify[target],
          }

          @notify { target: }

          Notify <| |>
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should include(edge)
        end

        it "should add edges defined on collected virtual resources" do
          Puppet[:code] = <<-MANIFEST
          @notify { source:
            before => Notify[target],
          }

          notify { target: }

          Notify <| |>
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should include(edge)
        end

        it "should add edges which refer to realized virtual resources" do
          Puppet[:code] = <<-MANIFEST
          notify { source:
            before => Notify[target],
          }

          @notify { target: }

          realize Notify[target]
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should include(edge)
        end

        it "should add edges defined on realized virtual resources" do
          Puppet[:code] = <<-MANIFEST
          @notify { source:
            before => Notify[target],
          }

          notify { target: }

          realize Notify[source]
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should include(edge)
        end

        it "should fail if an edge refers to an uncollected virtual resource" do
          Puppet[:code] = <<-MANIFEST
          notify { source:
            before => Notify[target],
          }

          @notify { target: }
          MANIFEST

          expect do
            subject.munge_catalog(catalog, Time.now.utc)
          end.to raise_error(Puppet::Error, "Invalid relationship: Notify[source] { before => Notify[target] }, because Notify[target] doesn't seem to be in the catalog")
        end

        it "should not add edges defined on an uncollected virtual resource" do
          Puppet[:code] = <<-MANIFEST
          @notify { source:
            before => Notify[target],
          }

          notify { target: }
          MANIFEST

          result = subject.munge_catalog(catalog, Time.now.utc)

          result['edges'].should_not include(edge)
        end
      end

      it "should produce an edge when referencing an aliased resource that only has a single namevar" do
        Puppet[:code] = <<-MANIFEST
        notify { 'noone':
          alias => 'another_thing'
        }
        notify { 'anyone':
          require => Notify['another_thing']
        }
        MANIFEST

        result = subject.munge_catalog(catalog, Time.now.utc)

        edge = {'source' => {'type' => 'Notify', 'title' => 'noone'},
                'target' => {'type' => 'Notify', 'title' => 'anyone'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      it "should produce an edge when referencing an aliased resource that supports composite namevars" do
        Puppet[:code] = <<-MANIFEST
        package { 'foo':
          ensure => present,
          alias => 'bar'
        }
        notify { 'hello':
          require => Package['bar']
        }
        MANIFEST

        result = subject.munge_catalog(catalog, Time.now.utc)

        edge = {'source' => {'type' => 'Package', 'title' => 'foo'},
                'target' => {'type' => 'Notify', 'title' => 'hello'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      it "should produce a reasonable error message for a missing 'before' relationship" do

        error = Gem::Version.new(Puppet.version) < Gem::Version.new("5.0.0") ?
          "Invalid relationship: Notify[anyone] { before => Notify[non-existent] }, because Notify[non-existent] doesn't seem to be in the catalog" :
          "Could not find resource 'Notify[non-existent]' in parameter 'before' at line 2 on node node"

        expect {
          resource[:before] = 'Notify[non-existent]'
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          subject.synthesize_edges(hash, catalog)
        }.to raise_error(Puppet::Error, error)
      end

      it "should produce a reasonable error message for a missing 'required-by' relationship" do
        error = Gem::Version.new(Puppet.version) < Gem::Version.new("5.0.0") ?
          "Invalid relationship: Notify[anyone] { require => Notify[non-existent] }, because Notify[non-existent] doesn't seem to be in the catalog" :
          "Could not find resource 'Notify[non-existent]' in parameter 'require' at line 2 on node node"

        expect {
          resource[:require] = 'Notify[non-existent]'
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          subject.synthesize_edges(hash, catalog)
        }.to raise_error(Puppet::Error, error)
      end

      it "should produce a reasonable error message for a missing 'notifies' relationship" do
        error = Gem::Version.new(Puppet.version) < Gem::Version.new("5.0.0") ?
          "Invalid relationship: Notify[anyone] { notify => Notify[non-existent] }, because Notify[non-existent] doesn't seem to be in the catalog" :
          "Could not find resource 'Notify[non-existent]' in parameter 'notify' at line 2 on node node"
        expect {
          resource[:notify] = 'Notify[non-existent]'
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          subject.synthesize_edges(hash, catalog)
        }.to raise_error(Puppet::Error, error)
      end

      it "should produce a reasonable error message for a missing 'subscription-of' relationship" do
        error = Gem::Version.new(Puppet.version) < Gem::Version.new("5.0.0") ?
          "Invalid relationship: Notify[anyone] { subscribe => Notify[non-existent] }, because Notify[non-existent] doesn't seem to be in the catalog" :
          "Could not find resource 'Notify[non-existent]' in parameter 'subscribe' at line 2 on node node"

        expect {
          resource[:subscribe] = 'Notify[non-existent]'
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          subject.synthesize_edges(hash, catalog)
        }.to raise_error(Puppet::Error, error)
      end

      it "should produce a reasonable error message for an invalid resourceref" do
        error = Gem::Version.new(Puppet.version) < Gem::Version.new("5.0.0") ?
          "Invalid relationship: Notify[anyone] { subscribe => Foobar::baz[name] }, because Foobar::baz[name] doesn't seem to be in the correct format. Resource references should be formatted as: Classname['title'] or Modulename::Classname['title'] (take careful note of the capitalization)." :
          "Could not find resource 'Foobar::baz[name]' in parameter 'subscribe' at line 2 on node node"

        expect {
          resource[:subscribe] = 'Foobar::baz[name]'
          hash = subject.add_parameters_if_missing(catalog_data_hash)
          subject.synthesize_edges(hash, catalog)
        }.to raise_error(Puppet::Error, error)
      end
    end #synthesize_edges

    describe "#munge_catalog" do
      it "should make an edge if the other end is referred to by its namevar" do
        other_resource = Puppet::Resource.new(:notify, 'noone', :parameters => {:name => 'completely_different'})
        resource[:require] = 'Notify[completely_different]'
        Puppet[:code] = [resource, other_resource].map(&:to_manifest).join

        result = subject.munge_catalog(catalog, Time.now.utc)

        edge = {'source' => {'type' => 'Notify', 'title' => 'noone'},
                'target' => {'type' => 'Notify', 'title' => 'anyone'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      context "when dealing with file resources and trailing slashes in their titles" do

        def test_file_require(resource_title, require_title)
          other_resource = Puppet::Resource.new(:file, resource_title)
          resource[:require] = "File[#{require_title}]"
          Puppet[:code] = [resource, other_resource].map(&:to_manifest).join
          result = subject.munge_catalog(catalog, Time.now.utc)

          edge = {'source' => {'type' => 'File', 'title' => resource_title},
                  'target' => {'type' => 'Notify', 'title' => 'anyone'},
                  'relationship' => 'required-by'}

          result['edges'].should include(edge)
        end

        it "should make an edge if the other end is a file resource with a missing trailing slash" do
          test_file_require('/tmp/foo/', '/tmp/foo')
        end

        it "should make an edge if the other end is a file resource with an extra trailing slash" do
          test_file_require('/tmp/foo', '/tmp/foo/')
        end

        it "should make an edge if the other end is a file resource with two missing trailing slashes" do
          test_file_require('/tmp/foo//', '/tmp/foo')
        end

        it "should make an edge if the other end is a file resource with two extra trailing slashes" do
          test_file_require('/tmp/foo', '/tmp/foo//')
        end
      end

      it "should make an edge if the other end is an exec referred to by an alias" do
        other_resource = Puppet::Resource.new(:exec, 'noone', :parameters => {:alias => 'completely_different', :path => '/anything'})
        resource[:require] = 'Exec[completely_different]'
        Puppet[:code] = [resource, other_resource].map(&:to_manifest).join

        result = subject.munge_catalog(catalog, Time.now.utc)

        edge = {'source' => {'type' => 'Exec', 'title' => 'noone'},
                'target' => {'type' => 'Notify', 'title' => 'anyone'},
                'relationship' => 'required-by'}

        result['edges'].should include(edge)
      end

      it "should not include virtual resources" do
        Puppet[:code] = <<-MANIFEST
        @notify { something: }
        MANIFEST

        result = subject.munge_catalog(catalog, Time.now.utc)

        result['resources'].each do |res|
          [res['type'], res['title']].should_not == ['Notify', 'something']
        end
      end

      it "should have the correct set of keys" do
        result = subject.munge_catalog(catalog, Time.now.utc)

        result.keys.should =~ ['certname', 'version', 'edges', 'resources',
          'transaction_uuid', 'environment', 'producer_timestamp', "code_id",
          "job_id", "catalog_uuid", "producer"]
      end

      context 'when dealing with TagSets' do
        let(:resource_hashes) do
          {
            'resources' => [
              {'type' => 'Stage',
              'title' => :main,
              'tags' => Puppet::Util::TagSet.new(['stage']),
              'exported' => false,
              'parameters' => {:name => 'main'}},
             {'type' => 'Class',
              'title' => 'Settings',
              'tags' => Puppet::Util::TagSet.new(['class', 'settings']),
              'exported' => false},
             {'type' => 'Class',
              'title' => :main,
              'tags' => Puppet::Util::TagSet.new(['class']),
              'exported' => false,
              'parameters' => {:name => 'main'}}
            ]
          }
        end

        it "should convert TagSet for resource['tags']" do
          subject.hashify_tags(resource_hashes)['resources'].each do |resource|
            resource['tags'].should be_an Array
          end
        end
      end
    end

    describe "#redact_sensitive_params" do
      let(:secret) {'xyzzy XKPGMKIB253ZVJHKOKZZXPQSSE'}
      let(:has_secret?) {
        lambda { |r| r['parameters'] && r['parameters'][:message] == secret}
      }
      let(:input) {{'tags' => ['settings'],
                    'name' => 'my_agent',
                    'version' => 1490991352,
                    'code_id' => nil,
                    'job_id' => nil,
                    'catalog_uuid' => 'aa4759d3-f1f1-47a0-925c-a4acd0c1b4ed',
                    'catalog_format' => 1,
                    'environment' => 'production',
                    'resources' =>
                    [{'type' => 'Stage',
                      'title' => :main,
                      'tags' => ['stage'],
                      'exported' => false,
                      'parameters' => {:name => 'main'}},
                     {'type' => 'Class',
                      'title' => 'Settings',
                      'tags' => ['class', 'settings'],
                      'exported' => false},
                     {'type' => 'Class',
                      'title' => :main,
                      'tags' => ['class'],
                      'exported' => false,
                      'parameters' => {:name => 'main'}},
                     {'type' => 'Notify',
                      'title' => 'hi',
                      'tags' => ['notify', 'hi', 'class'],
                      'file' =>  'site.pp',
                      'line' => 1,
                      'exported' => false,
                      'parameters' => {:message => secret},
                      'sensitive_parameters' => [:message]}],
                    'edges' =>
                    [{'source' => 'Stage[main]', 'target' => 'Class[Settings]'},
                     {'source' => 'Stage[main]', 'target' => 'Class[main]'},
                     {'source' => 'Class[main]', 'target' => 'Notify[hi]'}],
                    'classes' => ['settings']}}
      it 'should remove any sensitive resource parameters' do
        resources = input['resources']
        resources.any? {|r| r['sensitive_parameters']}.should be true
        resources.any? {|r| has_secret?.call(r) }.should be true
        subject.redact_sensitive_params(input)
        resources.any? {|r| r['sensitive_parameters']}.should_not be true
        resources.any? {|r| has_secret?.call(r) }.should_not be true
      end
    end
  end
end
