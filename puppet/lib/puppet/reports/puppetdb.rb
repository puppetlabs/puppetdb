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
    submit_command(self.host, report_to_hash, CommandStoreReport, 2)
  end

  # TODO: It seems unfortunate that we have to access puppet_version and
  # report_format directly as instance variables.  I've filed the following
  # ticket / pull req against puppet to expose them via accessors, which
  # seems more consistent and safer for the long-term.  However, for reasons
  # relating to backwards compatibility we won't be able to switch over to
  # the accessors until version 3.x of puppet is our oldest supported version.
  #
  # This was resolved in puppet 3.x via ticket #16139 (puppet pull request #1073).

  # @api private
  def report_format
    @report_format
  end

  # @api private
  def puppet_version
    @puppet_version
  end

  # Convert `self` (an instance of `Puppet::Transaction::Report`) to a hash
  # suitable for sending over the wire to PuppetDB
  #
  # @api private
  def report_to_hash
    add_v4_fields_to_report(
      {
        "certname"                => host,
        "puppet-version"          => puppet_version,
        "report-format"           => report_format,
        "configuration-version"   => configuration_version.to_s,
        "start-time"              => Puppet::Util::Puppetdb.to_wire_time(time),
        "end-time"                => Puppet::Util::Puppetdb.to_wire_time(time + run_duration),
        "resource-events"         => build_events_list
      })
  end

  # @api private
  def build_events_list
    filter_events(resource_statuses.inject([]) do |events, status_entry|
      _, status = *status_entry
      if ! (status.events.empty?)
        events.concat(status.events.map { |event| event_to_hash(status, event) })
      elsif status.skipped
        events.concat([fabricate_event(status, "skipped")])
      elsif status.failed
        # PP-254:
        #   We have to fabricate resource events here due to a bug/s in report providers
        #   that causes them not to include events on a resource status that has failed.
        #   When PuppetDB is able to make a hard break from older version of Puppet that
        #   have this bug, we can remove this behavior.
        events.concat([fabricate_event(status, "failure")])
      end
      events
    end)
  end

  # @api private
  def run_duration
    # TODO: this is wrong in puppet.  I am consistently seeing reports where
    # start-time + this value is less than the timestamp on the individual
    # resource events.  Not sure what the best short-term fix is yet; the long
    # term fix is obviously to make the correct data available in puppet.
    # I've filed a ticket against puppet here:
    #  http://projects.puppetlabs.com/issues/16480
    #
    # NOTE: failed reports have an empty metrics hash. Just send 0 for run time,
    #  since we don't have access to any better information.
    if metrics["time"] and metrics["time"]["total"]
      metrics["time"]["total"]
    else
      raise Puppet::Error, "Report from #{host} contained no metrics - possibly failed run. Not processing. (PDB-106)"
    end
  end

  # Convert an instance of `Puppet::Transaction::Event` to a hash
  # suitable for sending over the wire to PuppetDB
  #
  # @api private
  def event_to_hash(resource_status, event)
    add_v4_fields_to_event(resource_status,
      {
        "status"            => event.status,
        "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(event.time),
        "resource-type"     => resource_status.resource_type,
        "resource-title"    => resource_status.title,
        "property"          => event.property,
        "new-value"         => event.desired_value,
        "old-value"         => event.previous_value,
        "message"           => event.message,
        "file"              => resource_status.file,
        "line"              => resource_status.line
      })
  end

  # Given an instance of `Puppet::Resource::Status` and a status string,
  # this method fabricates a PuppetDB event object with the provided
  # `"status"`.
  #
  # @api private
  def fabricate_event(resource_status, event_status)
    add_v4_fields_to_event(resource_status,
      {
        "status"            => event_status,
        "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(resource_status.time),
        "resource-type"     => resource_status.resource_type,
        "resource-title"    => resource_status.title,
        "property"          => nil,
        "new-value"         => nil,
        "old-value"         => nil,
        "message"           => nil,
        "file"              => resource_status.file,
        "line"              => resource_status.line
      })
  end

  # Backwards compatibility with versions of Puppet prior to report format 4
  #
  # @api private
  def add_v4_fields_to_report(report_hash)
    if report_format >= 4
      report_hash.merge("transaction-uuid" => transaction_uuid)
    else
      report_hash.merge("transaction-uuid" => nil)
    end
  end

  # Backwards compatibility with versions of Puppet prior to report format 4
  #
  # @api private
  def add_v4_fields_to_event(resource_status, event_hash)
    if report_format >= 4
      event_hash.merge("containment-path" => resource_status.containment_path)
    else
      event_hash.merge("containment-path" => nil)
    end
  end

  # Filter out blacklisted events, if we're configured to do so
  #
  # @api private
  def filter_events(events)
    if config.ignore_blacklisted_events?
      events.select { |e| ! config.is_event_blacklisted?(e) }
    else
      events
    end
  end

  # Helper method for accessing the puppetdb configuration
  #
  # @api private
  def config
    Puppet::Util::Puppetdb.config
  end
end
