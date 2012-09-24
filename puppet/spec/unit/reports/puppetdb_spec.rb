#!/usr/bin/env rspec

require 'spec_helper'
require 'puppet/reports'
require 'net/http'
require 'puppet/network/http_pool'

processor = Puppet::Reports.report(:puppetdb)

describe processor do

  subject { Puppet::Transaction::Report.new("foo").extend(processor) }

  context "#process" do

    let(:http) { mock "http" }
    let(:httpok) { Net::HTTPOK.new('1.1', 200, '') }

    it "should POST the report command as a URL-encoded PSON string" do
      httpok.stubs(:body).returns '{"uuid": "a UUID"}'
      subject.stubs(:run_duration).returns(10)

      payload = {
          :command => Puppet::Util::Puppetdb::CommandSubmitReport,
          :version => 1,
          :payload => subject.send(:report_to_hash).to_pson
      }.to_pson

      Puppet::Network::HttpPool.expects(:http_instance).returns(http)
      http.expects(:post).with {|path, body, headers|
        path.should == Puppet::Util::Puppetdb::CommandsUrl
        match = /payload=(.+)/.match(CGI.unescape(body))
        match.should_not be_nil
        match[1].should == payload
      }.returns(httpok)

      subject.process
    end
  end

  context "#report_to_hash" do
    let (:resource) {
      resource = mock "resource"
      resource.stubs(:path).returns("foo")
      resource.stubs(:file).returns("foo")
      resource.stubs(:line).returns("foo")
      resource.stubs(:tags).returns([])
      resource.stubs(:title).returns("foo")
      resource
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
          result["resource-events"].should == []
        end
      end

      context "resource with events" do
        it "should include the resource" do
          event = Puppet::Transaction::Event.new()
          status.add_event(event)
          result = subject.send(:report_to_hash)
          result["resource-events"].length.should == 1
          event = result["resource-events"][0]
          event["resource-title"].should == "foo"
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
        end
      end
    end
  end

end
