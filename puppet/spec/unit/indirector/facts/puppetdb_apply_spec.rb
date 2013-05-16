require 'spec_helper'
require 'puppet/indirector/facts/puppetdb_apply'

describe Puppet::Node::Facts::PuppetdbApply do
  describe "#save" do
    let(:facts)    { Puppet::Node::Facts.new('foo') }

    def save
      subject.save(Puppet::Node::Facts.indirection.request(:save, facts.name, facts))
    end

    it "should only submit commands once" do
      subject.expects(:submit_command).once
      save
      save
      save
    end

  end

  describe "#find" do
    it "always returns nil to force the cache to be skipped" do
      subject.find('foo').should be_nil
    end
  end

end
