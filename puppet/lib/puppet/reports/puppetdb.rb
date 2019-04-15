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
      current_time = Time.now
      report_hash = report_to_hash(current_time)
      submit_command(self.host, report_hash, CommandStoreReport, 8, current_time.utc)
    end

    nil
  end

  # Convert `self` (an instance of `Puppet::Transaction::Report`) to a hash
  # suitable for sending over the wire to PuppetDB
  #
  # @return Hash[<String, Object>]
  # @api private
  def report_to_hash(producer_timestamp)
    profile("Convert report to wire format hash",
            [:puppetdb, :report, :convert_to_wire_format_hash]) do
      if environment.nil?
        raise Puppet::Error, "Environment is nil, unable to submit report. This may be due a bug with Puppet. Ensure you are running the latest revision, see PUP-2508 for more details."
      end

      resources = build_resources_list
      is_noop = (defined?(noop) && (not noop.nil?)) ?
                  noop :
                  resources.any? { |rs| has_noop_event?(rs) } && resources.none? { |rs| has_enforcement_event?(rs) }

      defaulted_catalog_uuid = defined?(catalog_uuid) ? catalog_uuid : transaction_uuid
      defaulted_job_id = defined?(job_id) ? job_id : nil
      defaulted_code_id = defined?(code_id) ? code_id : nil
      defaulted_cached_catalog_status = defined?(cached_catalog_status) ? cached_catalog_status : nil
      defaulted_noop_pending = defined?(noop_pending) ? noop_pending : nil
      defaulted_corrective_change = defined?(corrective_change) ? corrective_change : nil

      {
        "certname" => host,
        "job_id" => defaulted_job_id,
        "puppet_version" => puppet_version,
        "report_format" => report_format,
        "configuration_version" => configuration_version.to_s,
        "producer_timestamp" => Puppet::Util::Puppetdb.to_wire_time(producer_timestamp),
        "start_time" => Puppet::Util::Puppetdb.to_wire_time(time),
        "end_time" => Puppet::Util::Puppetdb.to_wire_time(time + run_duration),
        "environment" => environment,
        "transaction_uuid" => transaction_uuid,
        "status" => status,
        "noop" => is_noop,
        "noop_pending" => defaulted_noop_pending,
        "corrective_change" => defaulted_corrective_change,
        "logs" => build_logs_list,
        "metrics" => build_metrics_list,
        "resources" => resources,
        "catalog_uuid" => defaulted_catalog_uuid,
        "code_id" => defaulted_code_id,
        "cached_catalog_status" => defaulted_cached_catalog_status,
        "producer" => Puppet[:node_name_value]
      }
    end
  end

  # @return TrueClass
  # @api private
  def has_noop_event?(resource)
    resource["events"].any? { |event| event["status"] == 'noop' }
  end

  # @return TrueClass
  # @api private
  def has_enforcement_event?(resource)
    resource["events"].any? { |event| event["status"] != 'noop' }
  end

  # @return Array[Hash]
  # @api private
  def build_resources_list
    profile("Build resources list (count: #{resource_statuses.count})",
            [:puppetdb, :resources_list, :build]) do
      resources = resource_statuses.values.map { |resource| resource_status_to_hash(resource) }
      if ! config.include_unchanged_resources?
        resources.select{ |resource| (! resource["events"].empty?) or resource["skipped"] }
      else
        resources
      end
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
  def event_to_hash(event)
    corrective_change = defined?(event.corrective_change) ? event.corrective_change : nil
    {
      "status"            => event.status,
      "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(event.time),
      "name"              => event.name,
      "property"          => event.property,
      "new_value"         => event.desired_value.to_s,
      "old_value"         => event.previous_value.to_s,
      "corrective_change" => corrective_change,
      "message"           => event.message,
    }
  end

  def build_events_list(events)
    profile("Build events list (count: #{events.count})",
            [:puppetdb, :events_list, :build]) do
      events.map { |event| event_to_hash(event) }
    end
  end

  # Convert an instance of `Puppet::Resource::Status` to a hash
  # suitable for sending over the wire to PuppetDB
  #
  # @return Hash[<String, Object>]
  # @api private
  def resource_status_to_hash(resource_status)
    defaulted_corrective_change = defined?(resource_status.corrective_change) ? resource_status.corrective_change : nil
    {
      "skipped"           => resource_status.skipped,
      "timestamp"         => Puppet::Util::Puppetdb.to_wire_time(resource_status.time),
      "resource_type"     => resource_status.resource_type,
      "resource_title"    => resource_status.title.to_s,
      "file"              => resource_status.file,
      "line"              => resource_status.line,
      "containment_path"  => resource_status.containment_path,
      "corrective_change" => defaulted_corrective_change,
      "events"            => build_events_list(resource_status.events),
    }
  end

  # Helper method for accessing the puppetdb configuration
  #
  # @api private
  def config
    Puppet::Util::Puppetdb.config
  end
end
