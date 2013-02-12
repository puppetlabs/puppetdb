#!/usr/bin/env ruby

require 'spec_helper'
require 'puppet/rails'
require 'puppet/indirector/catalog/active_record'
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

  before :each do
    setup_scratch_database
    Puppet[:storeconfigs] = true
    Puppet[:storeconfigs_backend] = :active_record
  end

  describe "export action" do
    after :each do
      FileUtils.rm_f(@path)
    end

    before :each do
      file = Tempfile.new('export')
      @path = file.path
      file.close!
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
        result = subject.export(@path)

        result.should == ['foo']

        catalog = PSON.load(File.read(@path))

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
        result = subject.export(@path)

        result.should == ['foo']

        catalogs = File.readlines(@path).map {|line| PSON.load(line)}

        catalogs.length.should == 1
        data = catalogs.first['data']
        data['name'].should == 'foo'

        data['resources'].map {|resource| [resource['type'], resource['title']]}.should == [['Notify', 'exported']]
        data['resources'].should be_all {|resource| resource['exported'] == true}
      end

      it "should exclude nodes with no exported resources" do
        catalog = Puppet::Resource::Catalog.new('bar')

        catalog.add_resource notify('also not exported')

        save_catalog(catalog)

        result = subject.export(@path)

        result.should == ['foo']

        catalogs = File.readlines(@path).map {|line| PSON.load(line)}

        catalogs.map {|catalog| catalog['data']['name']}.should == ['foo']
      end
    end

    it "should do nothing if there are no nodes" do
      result = subject.export(@path)

      result.should == []

      File.read(@path).should be_empty
    end
  end
end
