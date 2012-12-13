require 'spec_helper'
require 'puppet/face'

describe Puppet::Face[:puppetdb, '0.0.1'] do
  it "should define a 'flush_queue' action" do
    subject.should be_action(:flush_queue)
  end

  describe "when flushing the queue" do
    # this is a stupid test, but there's not really anything else to test at
    # the moment.
    it "should call the Command.retry_queued_commands method" do
      Puppet::Util::Puppetdb::Command.expects(:retry_queued_commands).once
      subject.flush_queue
    end
  end

end
