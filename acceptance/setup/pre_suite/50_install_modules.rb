require 'lib/puppet_acceptance/dsl/install_utils'

extend PuppetAcceptance::DSL::InstallUtils

test_config = PuppetDBExtensions.config

step "Install the puppetdb/postgres modules and dependencies" do
  # TODO: change this to install from a less stupid place once the
  #  puppetdb module gets cleaned up
  install_from_git(database, "puppetlabs-puppetdb",
                   "git://github.com/cprice-puppet/puppetlabs-puppetdb.git",
                   "7c4b54a7f0ad13e1f8bc1e2b253abe8fda1cc01f")

  # TODO: change this to install from a less stupid place once the
  #  postgres module gets cleaned up
  install_from_git(database, "puppet-postgresql",
                   "git://github.com/cprice-puppet/puppet-postgresql.git",
                   "feature/master/align-with-puppetlabs-mysql")

  # TODO: change this to install from a tag once we do a 2.3.4 stdlib release
  install_from_git(database, "puppetlabs-stdlib",
                   "git://github.com/puppetlabs/puppetlabs-stdlib.git",
                   "e299ac6212b0468426f971b216447ef6bc679149")

  install_from_git(database, "puppetlabs-firewall",
                   "git://github.com/puppetlabs/puppetlabs-firewall.git",
                   "master")

  module_path = test_config[:db_module_path]
  on database, "rm #{module_path}"
  on database, "mkdir #{module_path}"
  on database, "ln -s #{SourcePath}/puppetlabs-puppetdb #{module_path}/puppetdb"
  on database, "ln -s #{SourcePath}/puppet-postgresql #{module_path}/postgresql"
  on database, "ln -s #{SourcePath}/puppetlabs-stdlib #{module_path}/stdlib"
  on database, "ln -s #{SourcePath}/puppetlabs-firewall #{module_path}/firewall"

end
