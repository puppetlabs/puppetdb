require 'puppet_docker_tools/spec_helper'

CURRENT_DIRECTORY = File.dirname(File.dirname(__FILE__))

describe 'Dockerfile' do
  include_context 'with a docker image'

  describe package('postgresql-common') do
    it { is_expected.to be_installed }
  end

  describe package('postgresql-9.6') do
    it { is_expected.to be_installed }
  end

  describe package('postgresql-contrib-9.6') do
    it { is_expected.to be_installed }
  end

  describe command('postgres --help') do
    its(:exit_status) { should eq 0 }
  end

  describe group('postgres') do
    it { should exist }
  end

  describe user('postgres') do
    it { should exist }
  end
end
