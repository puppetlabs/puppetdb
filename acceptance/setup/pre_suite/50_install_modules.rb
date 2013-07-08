require 'puppet_acceptance/dsl/install_utils'

extend PuppetAcceptance::DSL::InstallUtils

step "Install the puppetdb module and dependencies" do
  on master, "puppet module install puppetlabs/puppetdb"
end
