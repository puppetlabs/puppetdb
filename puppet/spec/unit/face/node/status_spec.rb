#!/usr/bin/env ruby

require 'spec_helper'
require 'puppet/face'

describe Puppet::Face[:node, :current] do
  let(:headers) do
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end

  it "should fail if no node is given" do
    expect { subject.status }.to raise_error ArgumentError, /provide at least one node/
  end

  it "should fetch the status of each node" do
    http = stub 'http'
    Puppet::Network::HttpPool.stubs(:http_instance).returns(http)

    nodes = %w[a b c d e]
    nodes.each do |node|
      http.expects(:get).with("/status/nodes/#{node}", headers)
    end

    subject.status(*nodes)
  end

  it "should CGI escape the node names" do
    http = stub 'http'
    Puppet::Network::HttpPool.stubs(:http_instance).returns(http)

    node = "foo/+*&bar"

    http.expects(:get).with("/status/nodes/foo%2F%2B%2A%26bar", headers)

    subject.status(node)
  end
end
