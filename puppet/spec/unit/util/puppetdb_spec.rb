#!/usr/bin/env rspec
# encoding: UTF-8

require 'spec_helper'
require 'digest/sha1'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'

Command                 = Puppet::Util::Puppetdb::Command


describe Puppet::Util::Puppetdb do
  subject { Object.new.extend described_class }

  describe "#submit_command" do
    let(:payload)        { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }
    let(:command1)  { Command.new("OPEN SESAME", 1, 'foo.localdomain',
                                  payload.merge(:uniqueprop => "command1")) }

    it "should submit the command" do
      # careful here... since we're going to stub Command.new, we need to
      # make sure we reference command1 first, because it calls Command.new.
      command1.expects(:submit).once
      Command.expects(:new).once.returns(command1)
      subject.submit_command(command1.certname,
                             command1.payload,
                             command1.command,
                             command1.version)
    end

  end

end
