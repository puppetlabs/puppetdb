#!/usr/bin/env rspec

require 'spec_helper'
require 'puppet/reports'
require 'net/http'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb/command_names'

processor = Puppet::Reports.report(:puppetdb)

describe processor do

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
          res_event["file"].should == "foo"
          res_event["line"].should == 1
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
    end
  end

end
