require 'puppet'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'


Puppet::Reports.register_report(:puppetdb) do
  include Puppet::Util::Puppetdb

  CommandStoreReport = Puppet::Util::Puppetdb::CommandNames::CommandStoreReport

  desc <<-DESC
  Send report information to PuppetDB via the REST API.  Reports are serialized to
  JSON format, and then submitted to puppetdb using the '#{CommandStoreReport}'
  command.
  DESC


  def process
    submit_command(self.host, report_to_hash, CommandStoreReport, 1)
  end

  private

  ### Convert `self` (an instance of `Puppet::Transaction::Report`) to a hash
  ### suitable for sending over the wire to PuppetDB
  def report_to_hash
    # TODO: It seems unfortunate that we have to access puppet_version and
    # report_format directly as instance variables.  I've filed the following
    # ticket / pull req against puppet to expose them via accessors, which
    # seems more consistent and safer for the long-term.  However, for reasons
    # relating to backwards compatibility we won't be able to switch over to
    # the accessors until version 3.x of puppet is our oldest supported version.
    #
    # This was resolved in puppet 3.x via ticket #16139 (puppet pull request #1073).
    {
      "certname"                => host,
      "puppet-version"          => @puppet_version,
      "report-format"           => @report_format,
      "configuration-version"   => configuration_version.to_s,
      "start-time"              => Puppet::Util::Puppetdb.to_wire_time(time),
      "end-time"                => Puppet::Util::Puppetdb.to_wire_time(time + run_duration),
      "resource-events"         =>
          resource_statuses.inject([]) do |events, status_entry|
            resource, status = *status_entry
            if ! (status.events.empty?)
              events.concat(
                  status.events.map do |event|
                    event_to_hash(status, event)
                  end)
            elsif status.skipped == true
              events.concat([resource_status_to_skipped_event_hash(status)])
            end
            events
          end
    }
  end

  def run_duration
    # TODO: this is wrong in puppet.  I am consistently seeing reports where
    # start-time + this value is less than the timestamp on the individual
    # resource events.  Not sure what the best short-term fix is yet; the long
    # term fix is obviously to make the correct data available in puppet.
    # I've filed a ticket against puppet here:
    #  http://projects.puppetlabs.com/issues/16480
    metrics["time"]["total"]
  end

  ## Convert an instance of `Puppet::Transaction::Event` to a hash
  ## suitable for sending over the wire to PuppetDB
  def event_to_hash(status, event)
    {
      "status"            => event.status,
      "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(event.time),
      "resource-type"     => status.resource_type,
      "resource-title"    => status.title,
      "property"          => event.property,
      "new-value"         => event.desired_value,
      "old-value"         => event.previous_value,
      "message"           => event.message,
      "file"              => status.file,
      "line"              => status.line
    }
  end


  ## Given an instance of `Puppet::Resource::Status` with
  ## a status of 'skipped', this method fabricates a PuppetDB
  ## event object representing the skipped resource.
  def resource_status_to_skipped_event_hash(resource_status)
    {
      "status"            => "skipped",
      "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(resource_status.time),
      "resource-type"     => resource_status.resource_type,
      "resource-title"    => resource_status.title,
      "property"          => nil,
      "new-value"         => nil,
      "old-value"         => nil,
      "message"           => nil,
    }
  end
  
end
