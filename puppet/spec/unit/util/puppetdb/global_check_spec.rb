require 'spec_helper'
require 'puppet/util/puppetdb'

describe Puppet::Util::Puppetdb::GlobalCheck do
  describe "#puppet_version_check" do
    it 'should throw exception if we are running an older version' do
      expect {
        Puppet::Util::Puppetdb::GlobalCheck.puppet_version_check("100.0.0")
      }.to raise_error(Puppet::Error, /You are attempting to use puppetdb-terminus on an unsupported version of Puppet/)
    end

    it 'should do nothing for newer versions' do
      Puppet::Util::Puppetdb::GlobalCheck.puppet_version_check("1.0.0")
    end
  end

  describe "#run" do
    it 'should do nothing as tests should only run on valid versions' do
      Puppet::Util::Puppetdb::GlobalCheck.run
    end
  end
end
