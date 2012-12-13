require 'puppet/face'
require 'puppet/util/puppetdb/command'

Puppet::Face.define(:puppetdb, '0.0.1') do
  copyright "Puppet Labs", 2012
  license   "Apache 2 license; see COPYING"

  summary "Perform tasks related to PuppetDB"

  description <<-EOT
    This subcommand allows you to perform various tasks relating to PuppetDB,
    such as retrying the submission of any PuppetDB commands that may have
    failed during previous agent runs.
  EOT

  action(:flush_queue) do
    summary "Retry any queued PuppetDB commands that were not successfully submitted on the first try"

    when_invoked do |name|
      Puppet::Util::Puppetdb::Command.retry_queued_commands
    end
  end

end
