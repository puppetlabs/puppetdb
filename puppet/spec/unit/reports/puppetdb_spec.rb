#!/usr/bin/env rspec

require 'spec_helper'
require 'puppet/reports'
require 'net/http'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/config'

processor = Puppet::Reports.report(:puppetdb)

describe processor do

  BlacklistedEvent = Puppet::Util::Puppetdb::Config::BlacklistedEvent

  subject {
    s = Puppet::Transaction::Report.new("foo").extend(processor)
    s.configuration_version = 123456789
    s
  }

  context "#process" do

    let(:http) { mock "http" }
    let(:httpok) { Net::HTTPOK.new('1.1', 200, '') }

    it "should POST the report command as a URL-encoded PSON string" do
      httpok.stubs(:body).returns '{"uuid": "a UUID"}'
      subject.stubs(:run_duration).returns(10)

      payload = {
          :command => Puppet::Util::Puppetdb::CommandNames::CommandStoreReport,
          :version => 1,
          :payload => subject.send(:report_to_hash)
      }.to_pson

      Puppet::Network::HttpPool.expects(:http_instance).returns(http)
      http.expects(:post).with {|path, body, headers|
        path.should == Puppet::Util::Puppetdb::Command::Url
        match = /payload=(.+)/.match(CGI.unescape(body))
        match.should_not be_nil
        match[1].should == payload
      }.returns(httpok)

      subject.process
    end
  end

  context "#report_to_hash" do
    let (:resource) {
      stub("resource",
        { :pathbuilder => ["foo", "bar", "baz"],
          :path => "foo",
          :file => "foo",
          :line => "foo",
          :tags => [],
          :title => "foo",
          :type => "foo" })
    }

    let (:status) {
        Puppet::Resource::Status.new(resource)
    }

    before :each do
      subject.add_resource_status(status)
    end

    context "start/end time" do
      before :each do
        subject.add_metric("time", {"total" => 10})
      end

      it "should base run duration off of the 'time'->'total' metric" do
        subject.send(:run_duration).should == 10
      end

      it "should use run_duration to calculate the end-time" do
        result = subject.send(:report_to_hash)
        duration = Time.parse(result["end-time"]) - Time.parse(result["start-time"])
        duration.should == subject.send(:run_duration)
      end
    end

    context "events" do
      before :each do
        subject.stubs(:run_duration).returns(10)
      end

      context "resource without events" do
        it "should not include the resource" do
          result = subject.send(:report_to_hash)
          # the server will populate the report id, so we validate that the
          # client doesn't include one
          result.has_key?("report").should be_false
          result["certname"].should == subject.host
          # TODO: change these two to use accessors as soon as we get up to puppet 3.0
          result["puppet-version"].should == subject.instance_variable_get(:@puppet_version)
          result["report-format"].should == subject.instance_variable_get(:@report_format)
          result["configuration-version"].should == subject.configuration_version.to_s
          result["resource-events"].should == []
        end
      end

      context "resource with events" do
        it "should include the resource" do
          event = Puppet::Transaction::Event.new()
          event.property = "fooprop"
          event.desired_value = "fooval"
          event.previous_value = "oldfooval"
          event.message = "foomessage"
          status.add_event(event)
          result = subject.send(:report_to_hash)
          result["resource-events"].length.should == 1
          res_event = result["resource-events"][0]
          res_event["resource-type"].should == "Foo"
          res_event["resource-title"].should == "foo"
          res_event["property"].should == "fooprop"
          res_event["new-value"].should == "fooval"
          res_event["old-value"].should == "oldfooval"
          res_event["message"].should == "foomessage"

        end
      end

      context "skipped resource" do
        it "should include the resource" do
          status.skipped = true
          result = subject.send(:report_to_hash)
          result["resource-events"].length.should == 1
          event = result["resource-events"][0]
          event["resource-title"].should == "foo"
          event["status"].should == "skipped"
          event["property"].should be_nil
          event["new-val"].should be_nil
          event["old-val"].should be_nil
        end
      end

      context "blacklisted events" do
        let (:config) {
          Puppet::Util::Puppetdb.config
        }

        before :each do
          config.send(:initialize_blacklisted_events, [
            BlacklistedEvent.new("Schedule", "weekly", "skipped", nil),
            BlacklistedEvent.new("Notify", "Hello there", "success", "message"),
          ])

          event = Puppet::Transaction::Event.new()
          event.property = "fooprop"
          event.desired_value = "fooval"
          event.previous_value = "oldfooval"
          event.message = "foomessage"
          status.add_event(event)

          schedule_resource =
              stub("schedule_resource",
                   { :pathbuilder => ["foo", "bar", "baz"],
                     :path => "foo",
                     :file => "foo",
                     :line => "foo",
                     :tags => [],
                     :type => "Schedule",
                     :title => "weekly" })
          schedule_resource_status = Puppet::Resource::Status.new(schedule_resource)
          schedule_resource_status.skipped = true
          subject.add_resource_status(schedule_resource_status)

          notify_resource =
              stub("notify_resource",
                   { :pathbuilder => ["foo", "bar", "baz"],
                     :path => "foo",
                     :file => "foo",
                     :line => "foo",
                     :tags => [],
                     :type => "Notify",
                     :title => "Hello there" })
          notify_status = Puppet::Resource::Status.new(notify_resource)
          notify_status.changed = true
          notify_event = Puppet::Transaction::Event.new()
          notify_event.status = "success"
          notify_event.property = "message"
          notify_status.add_event(notify_event)
          subject.add_resource_status(notify_status)
        end

        after :each do
          config.send(:initialize_blacklisted_events)
        end

        context "when blacklisted events are configured to be filtered" do
          it "should filter blacklisted events, but not other events" do
            result = subject.send(:report_to_hash)
            result["resource-events"].length.should == 1
            res_event = result["resource-events"][0]
            res_event["resource-type"].should == "Foo"
            res_event["resource-title"].should == "foo"
            res_event["property"].should == "fooprop"
            res_event["new-value"].should == "fooval"
            res_event["old-value"].should == "oldfooval"
            res_event["message"].should == "foomessage"
          end
        end

        context "when blacklisted events are configured not to be filtered" do
          it "should not filter anything" do
            config.stubs(:ignore_blacklisted_events?).returns(false)
            result = subject.send(:report_to_hash)
            result["resource-events"].length.should == 3
            foo_event = result["resource-events"][0]
            schedule_event = result["resource-events"][1]
            notify_event = result["resource-events"][2]
            foo_event["resource-type"].should == "Foo"
            foo_event["resource-title"].should == "foo"
            schedule_event["resource-type"].should == "Schedule"
            schedule_event["resource-title"].should == "weekly"
            notify_event["resource-type"].should == "Notify"
            notify_event["resource-title"].should == "Hello there"
          end
        end
      end

      context "default blacklist" do
        def add_skipped_schedule_event(title)
          resource = stub("#{title}_resource",
                   { :pathbuilder => ["foo", "bar", "baz"],
                     :path => "foo",
                     :file => "foo",
                     :line => "foo",
                     :tags => [],
                     :type => "Schedule",
                     :title => title })
          status = Puppet::Resource::Status.new(resource)
          status.skipped = true
          subject.add_resource_status(status)
        end

        before :each do
          event = Puppet::Transaction::Event.new()
          event.property = "fooprop"
          event.desired_value = "fooval"
          event.previous_value = "oldfooval"
          event.message = "foomessage"
          status.add_event(event)
        end

        it "should filter all of the blacklisted skipped schedule events" do
          add_skipped_schedule_event("never")
          add_skipped_schedule_event("puppet")
          add_skipped_schedule_event("hourly")
          add_skipped_schedule_event("daily")
          add_skipped_schedule_event("weekly")
          add_skipped_schedule_event("monthly")

          result = subject.send(:report_to_hash)
          result["resource-events"].length.should == 1
          res_event = result["resource-events"][0]
          res_event["resource-type"].should == "Foo"
          res_event["resource-title"].should == "foo"
        end
      end
    end
  end

end
