#!/usr/bin/env rspec
# encoding: UTF-8

require 'spec_helper'
require 'digest/sha1'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/http'
require 'json'


class FakeHttpResponse
  def initialize(body)
    @body = body
  end
  attr_reader :body
end

describe Puppet::Util::Puppetdb do
  subject { Object.new.extend described_class }

  describe "#submit_command" do
    let(:payload) { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }
    let(:command1) { Puppet::Util::Puppetdb::Command.new("OPEN SESAME", 1, 'foo.localdomain', Time.now.utc,
                                                         payload.merge(:uniqueprop => "command1")) }

    it "should submit the command" do
      # careful here... since we're going to stub Command.new, we need to
      # make sure we reference command1 first, because it calls Command.new.
      command1.expects(:submit).once
      Puppet::Util::Puppetdb::Command.expects(:new).once.returns(command1)
      subject.submit_command(command1.certname,
                             command1.command,
                             command1.producer_timestamp_utc,
                             command1.version) { command1.payload }
    end

  end

  describe ".query_puppetdb" do
    let(:response) { JSON.generate({'certname' => 'futile', 'status' => 'irrelevant'}) }
    let(:query) { ["=", "type", "Foo"] }
    let(:http_response) { FakeHttpResponse.new(response) }
    it "should query PuppetDB" do
      # careful here... since we're going to stub Command.new, we need to
      # make sure we reference command1 first, because it calls Command.new.
      Puppet::Util::Puppetdb::Http.expects(:action).once.returns(http_response)
      Puppet::Util::Puppetdb.query_puppetdb(query)
    end

  end

end
