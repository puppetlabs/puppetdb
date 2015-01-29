module Puppet::Util::Puppetdb
  class Blacklist

    BlacklistedEvent = Struct.new(:resource_type, :resource_title, :status, :property)

    # Initialize our blacklist of events to filter out of reports.  This is needed
    # because older versions of puppet always generate a swath of (meaningless)
    # 'skipped' Schedule events on every agent run.  As of puppet 3.3, these
    # events should no longer be generated, but this is here for backward compat.
    BlacklistedEvents =
        [BlacklistedEvent.new("Schedule", "never", "skipped", nil),
         BlacklistedEvent.new("Schedule", "puppet", "skipped", nil),
         BlacklistedEvent.new("Schedule", "hourly", "skipped", nil),
         BlacklistedEvent.new("Schedule", "daily", "skipped", nil),
         BlacklistedEvent.new("Schedule", "weekly", "skipped", nil),
         BlacklistedEvent.new("Schedule", "monthly", "skipped", nil)]

    def initialize(events)
      @events = events.inject({}) do |m, e|
        m[e.resource_type] ||= {}
        m[e.resource_type][e.resource_title] ||= {}
        m[e.resource_type][e.resource_title][e.status] ||= {}
        m[e.resource_type][e.resource_title][e.status][e.property] = true
        m
      end
    end

    def is_event_blacklisted?(event)
      @events.fetch(event["resource_type"], {}).
        fetch(event["resource_title"], {}).
        fetch(event["status"], {}).
        fetch(event["property"], false)
    end
  end
end
