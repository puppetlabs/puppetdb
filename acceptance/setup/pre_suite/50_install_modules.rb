require 'beaker/dsl/install_utils'

extend Beaker::DSL::InstallUtils

unless (test_config[:skip_presuite_provisioning])
  step "Install the puppetdb module and dependencies" do
    on databases, "puppet module install puppetlabs/puppetdb"
  end
end
