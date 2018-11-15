require 'puppet_docker_tools/spec_helper'

CURRENT_DIRECTORY = File.dirname(File.dirname(__FILE__))

describe 'Dockerfile' do
  include_context 'with a docker image'
  include_context 'with a docker container'

  ['puppet-agent', 'puppetdb', 'netcat', 'lsb-release'].each do |package_name|
    describe "#{package_name}" do
      it_should_behave_like 'a running container', "dpkg -l #{package_name}", 0, "#{package_name}"
    end
  end

  describe 'has /opt/puppetlabs/server/bin/puppetdb' do
    it_should_behave_like 'a running container', 'stat -L /opt/puppetlabs/server/bin/puppetdb', 0, 'Access: \(0755\/\-rwxr\-xr\-x\)'
  end

  describe 'puppetdb --help' do
    it_should_behave_like 'a running container', '/opt/puppetlabs/server/bin/puppetdb --help', 0
  end
end
