require 'puppet_docker_tools/spec_helper'

CURRENT_DIRECTORY = File.dirname(File.dirname(__FILE__))

describe 'Dockerfile' do
  include_context 'with a docker image'

  ['puppet-agent', 'puppetdb', 'netcat', 'lsb-release'].each do |package_name|
    describe package(package_name) do
      it { is_expected.to be_installed }
    end
  end

  describe file('/opt/puppetlabs/server/bin/puppetdb') do
    it { should exist }
    it { should be_executable }
  end

  describe 'Dockerfile#running' do
    include_context 'with a docker container'

    describe command('/opt/puppetlabs/server/bin/puppetdb --help') do
      its(:exit_status) { should eq 0 }
    end
  end
end
