require 'beaker/dsl/install_utils'

extend Beaker::DSL::InstallUtils

step "Install the puppetdb module and dependencies" do
  on databases, "puppet module install puppetlabs/puppetdb"
end
