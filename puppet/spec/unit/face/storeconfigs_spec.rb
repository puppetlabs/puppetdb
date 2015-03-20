#!/usr/bin/env ruby

require 'puppet/util/puppetdb'

if Puppet::Util::Puppetdb.puppet3compat?
  require 'spec_helper'
  require 'puppet/face/storeconfigs'
  require 'json'
  require 'puppet/util/feature'
  require 'puppet/util/puppetdb'

  describe Puppet::Face[:storeconfigs, '0.0.1'], :if => (Puppet.features.rails? && Puppet.features.sqlite?) do
    def setup_scratch_database
      Puppet::Rails.stubs(:database_arguments).returns(
        :adapter => 'sqlite3',
        :log_level => Puppet[:rails_loglevel],
        :database => ':memory:'
      )
      Puppet[:railslog]     = '/dev/null'
      Puppet::Rails.init
    end

    before :all do
      # We have to have this block to require this file, so they get loaded on
      # platforms where we are going to run the tests, but not on Ruby 1.8.5.
      # Unfortunately, rspec will evaluate the describe block (but not the before
      # block or tests) even if the conditions fail. The lack of a sqlite3 gem
      # for Ruby 1.8.5 ensures that the condition will always be false on Ruby
      # 1.8.5, so at this point it's safe to require this.
      require 'puppet/indirector/catalog/active_record'
    end

    before :each do
      setup_scratch_database
      Puppet[:storeconfigs] = true
      Puppet[:storeconfigs_backend] = :active_record
    end

    describe "export action" do
      after :each do
        FileUtils.rm_rf(@path)
      end

      before :each do
        tempfile = Tempfile.new('export')
        @path = tempfile.path
        tempfile.close!

        Dir.mkdir(@path)

        subject.stubs(:destination_file).returns File.join(@path, 'storeconfigs-test.tar')
      end

      # Turn the filename of a gzipped tar into a hash from filename to content.
      def tgz_to_hash(filename)
        # List the files in the archive, ignoring directories (whose names end
        # with /), and stripping the leading puppetdb-bak.
        files = `tar tf #{filename}`.lines.map(&:chomp).reject { |fname| fname[-1,1] == '/'}.map {|fname| fname.sub('puppetdb-bak/', '') }

        # Get the content of the files, one per line. Thank goodness they're a
        # single line each.
        content = `tar xf #{filename} -O`.lines.to_a

        # Build a hash from filename to content. Ruby 1.8.5 doesn't like
        # Hash[array_of_pairs], so we have to jump through hoops by flattening
        # and splatting this list.
        Hash[*files.zip(content).flatten]
      end

      describe "with nodes present" do
        def notify(title, exported=false)
          Puppet::Resource.new(:notify, title, :parameters => {:message => title}, :exported => exported)
        end

        def user(name)
          Puppet::Resource.new(:user, name,
                               :parameters => {:groups => ['foo', 'bar', 'baz'],
                                               :profiles => ['stuff', 'here'] #<-- Uses an ordered list
                                              }, :exported => true)
        end

        def save_catalog(catalog)
          request = Puppet::Resource::Catalog.indirection.request(:save, catalog.name, catalog)
          Puppet::Resource::Catalog::ActiveRecord.new.save(request)
        end

        before :each do
          catalog = Puppet::Resource::Catalog.new('foo')

          catalog.add_resource notify('not exported')
          catalog.add_resource notify('exported', true)
          catalog.add_resource user('someuser')
          save_catalog(catalog)
        end

        it "should have the right structure" do
          filename = subject.export

          results = tgz_to_hash(filename)

          results.keys.should =~ ['export-metadata.json', 'catalogs/foo.json']

          metadata = JSON.load(results['export-metadata.json'])

          metadata.keys.should =~ ['timestamp', 'command_versions']
          metadata['command_versions'].should == {'replace_catalog' => 6}

          catalog = JSON.load(results['catalogs/foo.json'])

          catalog.keys.should =~ ['metadata', 'environment', 'certname', 'version', 'edges', 'resources', 'timestamp', 'producer_timestamp']

          catalog['metadata'].should == {'api_version' => 1}

          catalog['certname'].should == 'foo'
          catalog['edges'].to_set.should == [{
                                            'source' => {'type' => 'Stage', 'title' => 'main'},
                                            'target' => {'type' => 'Notify', 'title' => 'exported'},
                                            'relationship' => 'contains'},
                                          {"source"=>{"type"=>"Stage", "title"=>"main"},
                                           "target"=>{"type"=>"User", "title"=>"someuser"},
                                           "relationship"=>"contains"}].to_set

          catalog['resources'].should include({
                                             'type'       => 'Stage',
                                             'title'      => 'main',
                                             'exported'   => false,
                                             'tags'       => ['stage', 'main'],
                                             'parameters' => {},
                                           })

          catalog['resources'].should include({
                                             'type'       => 'Notify',
                                             'title'      => 'exported',
                                             'exported'   => true,
                                             'tags'       => ['exported', 'notify'],
                                             'parameters' => {
                                               'message' => 'exported',
                                             },
                                           })

          catalog['resources'].should include({
                                             'type'       => 'User',
                                             'title'      => 'someuser',
                                             'exported'   => true,
                                             'tags'       => ['someuser', 'user'],
                                             'parameters' => {
                                               'groups'   => ['foo', 'bar', 'baz'],
                                               'profiles' => ['stuff', 'here']
                                             },
                                           })
        end

        it "should only include exported resources and Stage[main]" do
          filename = subject.export

          results = tgz_to_hash(filename)

          results.keys.should =~ ['export-metadata.json', 'catalogs/foo.json']

          catalog = JSON.load(results['catalogs/foo.json'])

          catalog['certname'].should == 'foo'

          catalog['edges'].map do |edge|
            [edge['source']['type'], edge['source']['title'], edge['relationship'], edge['target']['type'], edge['target']['title']]
          end.to_set.should == [['Stage', 'main', 'contains', 'Notify', 'exported'],
                                ['Stage', 'main', 'contains', 'User', 'someuser']].to_set

          catalog['resources'].map { |resource| [resource['type'], resource['title']] }.to_set.should == [['Notify', 'exported'], ["User", "someuser"], ['Stage', 'main']].to_set

          notify = catalog['resources'].find {|resource| resource['type'] == 'Notify'}

          notify['exported'].should == true
        end

        it "should exclude nodes with no exported resources" do
          catalog = Puppet::Resource::Catalog.new('bar')

          catalog.add_resource notify('also not exported')

          save_catalog(catalog)

          filename = subject.export

          results = tgz_to_hash(filename)

          results.keys.should =~ ['export-metadata.json', 'catalogs/foo.json']
        end
      end

      it "should do nothing if there are no nodes" do
        filename = subject.export

        results = tgz_to_hash(filename)
        results.keys.should == ['export-metadata.json']
      end
    end
  end
end
