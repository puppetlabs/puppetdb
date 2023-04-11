require 'beaker/dsl/install_utils'

extend Beaker::DSL::InstallUtils

unless (test_config[:skip_presuite_provisioning])
  step "Install the puppetdb module and dependencies" do
    install_puppetdb_module(databases, puppet_repo_version(test_config[:platform_version],
                                                           test_config[:install_mode],
                                                           test_config[:nightly]))
  end
end
