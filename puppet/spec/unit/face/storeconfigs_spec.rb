#!/usr/bin/env ruby

require 'spec_helper'
require 'puppet/face/storeconfigs'

describe Puppet::Face[:storeconfigs, '0.0.1'], :if => (Puppet.features.sqlite? and Puppet.features.rails?) do
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
      files = `tar tf #{filename}`.lines.map(&:chomp).reject {|filename| filename[-1] == '/'}.map {|filename| filename.sub('puppetdb-bak/', '')}

      # Get the content of the files, one per line. Thank goodness they're a
      # single line each.
      content = `tar xf #{filename} -O`.lines

      # Build a hash from filename to content. Ruby 1.8.5 doesn't like
      # Hash[array_of_pairs], so we have to jump through hoops by flattening
      # and splatting this list.
      Hash[*files.zip(content).flatten]
    end

    describe "with nodes present" do
      def notify(title, exported=false)
        Puppet::Resource.new(:notify, title, :parameters => {:message => title}, :exported => exported)
      end

      def save_catalog(catalog)
        request = Puppet::Resource::Catalog.indirection.request(:save, catalog.name, catalog)
        Puppet::Resource::Catalog::ActiveRecord.new.save(request)
      end

      before :each do
        catalog = Puppet::Resource::Catalog.new('foo')

        catalog.add_resource notify('not exported')
        catalog.add_resource notify('exported', true)

        save_catalog(catalog)
      end

      it "should have the right structure" do
        filename = subject.export

        results = tgz_to_hash(filename)

        results.keys.should =~ ['export-metadata.json', 'catalogs/foo.json']

        metadata = PSON.load(results['export-metadata.json'])

        metadata.keys.should =~ ['timestamp', 'command-versions']
        metadata['command-versions'].should == {'replace-catalog' => 2}

        catalog = PSON.load(results['catalogs/foo.json'])

        catalog.keys.should =~ ['metadata', 'data']

        catalog['metadata'].should == {'api_version' => 1}

        data = catalog['data']

        data.keys.should =~ ['name', 'version', 'edges', 'resources']

        data['name'].should == 'foo'
        data['edges'].should == []

        data['resources'].first.should == {
          'type'       => 'Notify',
          'title'      => 'exported',
          'exported'   => true,
          'tags'       => ['exported', 'notify'],
          'aliases'    => [],
          'file'       => nil,
          'line'       => nil,
          'parameters' => {
            'message' => 'exported',
          },
        }
      end

      it "should only include exported resources" do
        filename = subject.export

        results = tgz_to_hash(filename)

        results.keys.should =~ ['export-metadata.json', 'catalogs/foo.json']

        catalog = PSON.load(results['catalogs/foo.json'])

        data = catalog['data']
        data['name'].should == 'foo'

        data['resources'].map {|resource| [resource['type'], resource['title']]}.should == [['Notify', 'exported']]
        data['resources'].should be_all {|resource| resource['exported'] == true}
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
