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

  # Process the report by formatting it into a PuppetDB 'store report'
  # command and submitting it.
  #
  # @return [void]
  def process
    profile("report#process", [:puppetdb, :report, :process]) do
      submit_command(self.host, report_to_hash, CommandStoreReport, 5)
    end

    nil
  end

  # Convert `self` (an instance of `Puppet::Transaction::Report`) to a hash
  # suitable for sending over the wire to PuppetDB
  #
  # @return Hash[<String, Object>]
  # @api private
  def report_to_hash
    profile("Convert report to wire format hash",
            [:puppetdb, :report, :convert_to_wire_format_hash]) do
      if environment.nil?
        raise Puppet::Error, "Environment is nil, unable to submit report. This may be due a bug with Puppet. Ensure you are running the latest revision, see PUP-2508 for more details."
      end

      resource_events = build_events_list
      is_noop = resource_events.any? {|rs| rs["status"] == 'noop'} && resource_events.none? {|rs| rs["status"] == 'failed'}

      {
        "certname"                => host,
        "puppet_version"          => puppet_version,
        "report_format"           => report_format,
        "configuration_version"   => configuration_version.to_s,
        "producer_timestamp"      => Puppet::Util::Puppetdb.to_wire_time(Time.now),
        "start_time"              => Puppet::Util::Puppetdb.to_wire_time(time),
        "end_time"                => Puppet::Util::Puppetdb.to_wire_time(time + run_duration),
        "resource_events"         => resource_events,
        "environment"             => environment,
        "transaction_uuid"        => transaction_uuid,
        "status"                  => status,
        "noop"                    => is_noop,
        "logs"                    => build_logs_list,
        "metrics"                 => build_metrics_list,
      }
    end
  end

  # @return Array[Hash]
  # @api private
  def build_events_list
    profile("Build events list (count: #{resource_statuses.count})",
            [:puppetdb, :events_list, :build]) do
      filter_events(resource_statuses.inject([]) do |events, status_entry|
        _, status = *status_entry
        if ! (status.events.empty?)
          events.concat(status.events.map { |event| event_to_hash(status, event) })
        elsif status.skipped
          events.concat([fabricate_event(status, "skipped")])
        end
        events
      end)
    end
  end

  # @return Array[Hash]
  # @api private
  def build_logs_list
    profile("Build logs list (count: #{logs.count})",
            [:puppetdb, :logs_list, :build]) do
      logs.map do |log|
        {
          'file' => log.file,
          'line' => log.line,
          'level' => log.level,
          'message' => log.message,
          'source' => log.source,
          'tags' => [*log.tags],
          'time' => Puppet::Util::Puppetdb.to_wire_time(log.time),
        }
      end
    end
  end

  # @return Array[Hash}
  # @api private
  def build_metrics_list
    profile("Build metrics list (count: #{metrics.count})",
            [:puppetdb, :metrics_list, :build]) do
      metrics_list = []
      metrics.each do |name, data|
        metric_hashes = data.values.map {|x| {"category" => data.name, "name" => x.first, "value" => x.last}}
        metrics_list.concat(metric_hashes)
      end
      metrics_list
    end
  end


  # @return Number
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
      0
    end
  end

  # Convert an instance of `Puppet::Transaction::Event` to a hash
  # suitable for sending over the wire to PuppetDB
  #
  # @return Hash[<String, Object>]
  # @api private
  def event_to_hash(resource_status, event)
    {
      "status"            => event.status,
      "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(event.time),
      "resource_type"     => resource_status.resource_type,
      "resource_title"    => resource_status.title.to_s,
      "property"          => event.property,
      "new_value"         => event.desired_value,
      "old_value"         => event.previous_value,
      "message"           => event.message,
      "file"              => resource_status.file,
      "line"              => resource_status.line,
      "containment_path"  => resource_status.containment_path,
    }
  end

  # Given an instance of `Puppet::Resource::Status` and a status
  # string, this method fabricates a PuppetDB event object with the
  # provided `"status"`.
  #
  # @api private
  def fabricate_event(resource_status, event_status)
    {
      "status"            => event_status,
      "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(resource_status.time),
      "resource_type"     => resource_status.resource_type,
      "resource_title"    => resource_status.title.to_s,
      "property"          => nil,
      "new_value"         => nil,
      "old_value"         => nil,
      "message"           => nil,
      "file"              => resource_status.file,
      "line"              => resource_status.line,
      "containment_path" => resource_status.containment_path,
    }
  end

  # Filter out blacklisted events, if we're configured to do so
  #
  # @api private
  def filter_events(events)
    if config.ignore_blacklisted_events?
      profile("Filter blacklisted events",
              [:puppetdb, :events, :filter_blacklisted]) do
        events.select { |e| ! config.is_event_blacklisted?(e) }
      end
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
