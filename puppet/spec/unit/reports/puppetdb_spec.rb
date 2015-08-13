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

    def without_producer_timestamp(json_body)
      parsed = JSON.parse(json_body)
      parsed["payload"].delete("producer_timestamp")
      parsed.to_json
    end

    it "should POST the report command as a URL-encoded JSON string" do
      httpok.stubs(:body).returns '{"uuid": "a UUID"}'
      subject.stubs(:run_duration).returns(10)

      expected_body = {
        :command => Puppet::Util::Puppetdb::CommandNames::CommandStoreReport,
        :version => 6,
        :payload => subject.send(:report_to_hash)
      }.to_json

      Puppet::Network::HttpPool.expects(:http_instance).returns(http)
      http.expects(:post).with {|path, body, headers|
        expect(path).to include(Puppet::Util::Puppetdb::Command::CommandsUrl)

        # producer_timestamp is generated at submission time, so remove it from
        # the comparison
        expect(without_producer_timestamp(body)).to eq(without_producer_timestamp(expected_body))
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
          result["resources"].should == []
          result["noop"].should == false
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

          result["resources"].length.should == 1
          res = result["resources"][0]
          res["resource_type"].should == "Foo"
          res["resource_title"].should == "foo"
          res["file"].should == "foo"
          res["line"].should == 1
          res["containment_path"].should == ["foo", "bar", "baz"]
          res["events"].length.should == 1

          res_event = res["events"][0]
          res_event["property"].should == "fooprop"
          res_event["new_value"].should == "fooval"
          res_event["old_value"].should == "oldfooval"
          res_event["message"].should == "foomessage"
        end
      end

      context "skipped resource status" do
        it "should include the resource" do
          status.skipped = true
          result = subject.send(:report_to_hash)

          result["resources"].length.should == 1
          resource = result["resources"][0]
          resource["resource_type"].should == "Foo"
          resource["resource_title"].should == "foo"
          resource["containment_path"].should == ["foo", "bar", "baz"]
          resource["events"].length.should == 0
        end
      end

      context "failed resource status" do
        before :each do
          status.stubs(:failed).returns(true)
        end

        context "with no events" do
          it "should have no events" do
            result = subject.send(:report_to_hash)
            result["resources"].length.should == 0
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
            result["resources"].length.should == 1
            resource = result["resources"][0]
            resource["resource_type"].should == "Foo"
            resource["resource_title"].should == "foo"
            resource["file"].should == "foo"
            resource["line"].should == 1
            resource["containment_path"].should == ["foo", "bar", "baz"]
            resource["events"].length.should == 1

            res_event = resource["events"][0]
            res_event["property"].should == "barprop"
            res_event["new_value"].should == "barval"
            res_event["old_value"].should == "oldbarval"
            res_event["message"].should == "barmessage"
          end
        end
      end

      context "with unchanged resources turned on" do
        let (:config) {
          Puppet::Util::Puppetdb.config
        }

        before :each do
          config.stubs(:include_unchanged_resources?).returns(true)

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
          notify_status.changed = false
          subject.add_resource_status(notify_status)

          event = Puppet::Transaction::Event.new()
          event.property = "fooprop"
          event.desired_value = "fooval"
          event.previous_value = "oldfooval"
          event.message = "foomessage"
          status.add_event(event)
        end

        context "with an unchanged resource" do
          it "should include the actual event" do
            result = subject.send(:report_to_hash)
            unchanged_resources = result["resources"].select { |res| res["events"].empty? and ! (res["skipped"])}
            unchanged_resources.length.should == 1
            resource = unchanged_resources[0]
            resource["resource_type"].should == "Notify"
            resource["resource_title"].should == "Hello there"
            resource["file"].should == "foo"
            resource["line"].should == "foo"
            resource["containment_path"].should == ["foo", "bar", "baz"]
            resource["events"].length.should == 0
          end
        end

      end

    end
  end
end
