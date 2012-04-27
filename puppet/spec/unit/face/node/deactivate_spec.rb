#!/usr/bin/env ruby

require 'spec_helper'
require 'puppet/face'
require 'puppet/indirector/node/puppetdb'

describe Puppet::Face[:node, :current] do
  it "should fail if no node is given" do
    expect { subject.deactivate }.to raise_error ArgumentError, /provide at least one node/
  end

  it "should deactivate each node using the puppetdb terminus" do
    nodes = ['a', 'b', 'c']
    nodes.each do |node|
      Puppet::Node::Puppetdb.any_instance.expects(:destroy).with do |request|
        request.key == node
      end.returns('uuid' => "uuid_#{node}")
    end

    subject.deactivate(*nodes).should == {
      'a' => 'uuid_a',
      'b' => 'uuid_b',
      'c' => 'uuid_c',
    }
  end
end
