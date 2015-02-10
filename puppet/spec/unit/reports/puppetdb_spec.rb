#!/usr/bin/env rspec

require 'spec_helper'
require 'puppet/reports'
require 'net/http'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/config'
require 'json'

processor = Puppet::Reports.report(:puppetdb)

describe processor do

  subject {
    s = Puppet::Transaction::Report.new("foo").extend(processor)
    s.configuration_version = 123456789
    s.environment = "foo"
    s
  }

  context "#process" do

    let(:http) { mock "http" }
    let(:httpok) { Net::HTTPOK.new('1.1', 200, '') }

    it "should POST the report command as a URL-encoded JSON string" do
      httpok.stubs(:body).returns '{"uuid": "a UUID"}'
      subject.stubs(:run_duration).returns(10)

      payload = {
        :command => Puppet::Util::Puppetdb::CommandNames::CommandStoreReport,
        :version => 5,
        :payload => subject.send(:report_to_hash),
      }.to_json

      Puppet::Network::HttpPool.expects(:http_instance).returns(http)
      http.expects(:post).with {|path, body, headers|
        expect(path).to include(Puppet::Util::Puppetdb::Command::CommandsUrl)
        expect(body).to eq(payload)
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
          :line => 1,
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

    it "should include the transaction uuid or nil" do
      subject.transaction_uuid = 'abc123'
      result = subject.send(:report_to_hash)
      result["transaction_uuid"].should == 'abc123'
    end

    context "start/end time" do
      before :each do
        subject.add_metric("time", {"total" => 10})
      end

      it "should base run duration off of the 'time'->'total' metric" do
        subject.send(:run_duration).should == 10
      end

      it "should use run_duration to calculate the end_time" do
        result = subject.send(:report_to_hash)
        duration = Time.parse(result["end_time"]) - Time.parse(result["start_time"])
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
          result["puppet_version"].should == subject.puppet_version
          result["report_format"].should == subject.report_format
          result["configuration_version"].should == subject.configuration_version.to_s
          result["resource_events"].should == []
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
          result["resource_events"].length.should == 1
          res_event = result["resource_events"][0]
          res_event["resource_type"].should == "Foo"
          res_event["resource_title"].should == "foo"
          res_event["property"].should == "fooprop"
          res_event["new_value"].should == "fooval"
          res_event["old_value"].should == "oldfooval"
          res_event["message"].should == "foomessage"
          res_event["file"].should == "foo"
          res_event["line"].should == 1
          res_event["containment_path"].should == ["foo", "bar", "baz"]
        end
      end

      context "skipped resource status" do
        it "should include the resource" do
          status.skipped = true
          result = subject.send(:report_to_hash)
          result["resource_events"].length.should == 1
          event = result["resource_events"][0]
          event["resource_type"].should == "Foo"
          event["resource_title"].should == "foo"
          event["status"].should == "skipped"
          event["property"].should be_nil
          event["new_val"].should be_nil
          event["old_val"].should be_nil
          event["message"].should be_nil
          event["containment_path"].should == ["foo", "bar", "baz"]
        end
      end

      context "failed resource status" do
        before :each do
          status.stubs(:failed).returns(true)
        end

        context "with no events" do
          it "should have no events" do
            result = subject.send(:report_to_hash)
            result["resource_events"].length.should == 0
          end
        end

        context "with events" do
          it "should include the actual event" do
            event = Puppet::Transaction::Event.new
            event.property = "barprop"
            event.desired_value = "barval"
            event.previous_value = "oldbarval"
            event.message = "barmessage"
            status.add_event(event)

            result = subject.send(:report_to_hash)
            result["resource_events"].length.should == 1
            res_event = result["resource_events"][0]
            res_event["resource_type"].should == "Foo"
            res_event["resource_title"].should == "foo"
            res_event["property"].should == "barprop"
            res_event["new_value"].should == "barval"
            res_event["old_value"].should == "oldbarval"
            res_event["message"].should == "barmessage"
            res_event["file"].should == "foo"
            res_event["line"].should == 1
            res_event["containment_path"].should == ["foo", "bar", "baz"]
          end
        end
      end

      context "blacklisted events" do
        BlacklistedEvent = Puppet::Util::Puppetdb::Blacklist::BlacklistedEvent

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
            result["resource_events"].length.should == 1
            res_event = result["resource_events"][0]
            res_event["resource_type"].should == "Foo"
            res_event["resource_title"].should == "foo"
            res_event["property"].should == "fooprop"
            res_event["new_value"].should == "fooval"
            res_event["old_value"].should == "oldfooval"
            res_event["message"].should == "foomessage"
            res_event["containment_path"].should == ["foo", "bar", "baz"]
          end
        end

        context "when blacklisted events are configured not to be filtered" do
          it "should not filter anything" do
            config.stubs(:ignore_blacklisted_events?).returns(false)
            result = subject.send(:report_to_hash)
            result["resource_events"].length.should == 3
            [["Foo", "foo"],
             ["Schedule", "weekly"],
             ["Notify", "Hello there"]].each do |type, title|
              matches = result["resource_events"].select do |e|
                  e["resource_type"] == type and e["resource_title"] == title
              end
              matches.length.should be(1), "Expected to find an event with type '#{type}' and title '#{title}'"
            end
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
          result["resource_events"].length.should == 1
          res_event = result["resource_events"][0]
          res_event["resource_type"].should == "Foo"
          res_event["resource_title"].should == "foo"
        end
      end
    end
  end
end
