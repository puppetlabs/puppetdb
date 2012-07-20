#!/usr/bin/env ruby

step "Stop puppetdb" do
  stop_puppetdb(database)
end
