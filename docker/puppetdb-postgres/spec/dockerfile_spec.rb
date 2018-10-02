require 'puppet_docker_tools/spec_helper'

CURRENT_DIRECTORY = File.dirname(File.dirname(__FILE__))

describe 'Dockerfile' do
  include_context 'with a docker image'
  include_context 'with a docker container' do
    def docker_run_options
      '-e POSTGRES_PASSWORD=puppetdb -e POSTGRES_USER=puppetdb'
    end
  end

  ['postgresql-common', 'postgresql-9.6', 'postgresql-contrib-9.6'].each do |package_name|
    describe "#{package_name}" do
      it_should_behave_like 'a running container', "dpkg -l #{package_name}"
    end
  end

  describe 'postgres --help' do
    it_should_behave_like 'a running container', 'postgres --help', 0
  end

  describe 'postgres group' do
    it_should_behave_like 'a running container', 'getent group postgres', 0
  end

  describe 'postgres user' do
    it_should_behave_like 'a running container', 'id postgres', 0
  end
end
