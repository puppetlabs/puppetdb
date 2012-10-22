require 'puppet'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/report_helper'


Puppet::Reports.register_report(:puppetdb) do

  CommandSubmitReport = Puppet::Util::Puppetdb::CommandSubmitReport

  desc <<-DESC
  Send report information to PuppetDB via the REST API.  Reports are serialized to
  JSON format, and then submitted to puppetdb using the '#{CommandSubmitReport}'
  command.
  DESC


  def process
    helper = Puppet::Util::Puppetdb::ReportHelper.new
    helper.submit_command(self.host, report_to_hash, CommandSubmitReport, 1)
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

    # Choosing a somewhat arbitrary string for group-id for the time being.
    { "group-id" => "#{host}|puppet-#{@puppet_version}|#{@report_format}|#{configuration_version}|#{time}",
      "start-time" => Puppet::Util::Puppetdb.to_wire_time(time),
      "end-time" => Puppet::Util::Puppetdb.to_wire_time(time + run_duration),
      "resource-events" =>
          resource_statuses.inject([]) do |events, status_entry|
            resource, status = *status_entry
            if ! (status.events.empty?)
              events.concat(
                  status.events.map do |event|
                    event_to_hash(status.resource_type, status.title, event)
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
    #  start-time + this value is less than the timestamp on the individual
    #  resource events.  Not sure what the best short-term fix is yet; the long
    #  term fix is obviously to make the correct data available in puppet.
    metrics["time"]["total"]
  end

  ## Convert an instance of `Puppet::Transaction::Event` to a hash
  ## suitable for sending over the wire to PuppetDB
  def event_to_hash(resource_type, resource_title, event)
    result = {}
    result["certname"]          = host
    result["status"]            = event.status
    result["timestamp"]         = Puppet::Util::Puppetdb.to_wire_time(event.time)
    result["resource-type"]     = resource_type
    result["resource-title"]    = resource_title
    result["property-name"]     = event.property
    result["property-value"]    = event.desired_value
    result["previous-value"]    = event.previous_value
    result["message"]           = event.message

    result
  end


  ## Given an instance of `Puppet::Resource::Status` with
  ## a status of 'skipped', this method fabricates a PuppetDB
  ## event object representing the skipped resource.
  def resource_status_to_skipped_event_hash(resource_status)
    result = {}
    result["certname"]          = host
    result["status"]            = "skipped"
    result["timestamp"]         = Puppet::Util::Puppetdb.to_wire_time(resource_status.time)
    result["resource-type"]     = resource_status.resource_type
    result["resource-title"]    = resource_status.title
    result["property-name"]     = nil
    result["property-value"]    = nil
    result["previous-value"]    = nil
    result["message"]           = nil

    result
  end
  
end
