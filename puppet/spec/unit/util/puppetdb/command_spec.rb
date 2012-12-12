require 'spec_helper'
require 'digest/sha1'
require 'puppet/util/puppetdb'


describe Puppet::Util::Puppetdb::Command do
  let(:payload) { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }
  let(:subject) { described_class.new("OPEN SESAME", 1,
                                      'foo.localdomain', payload) }

  describe "public class methods" do
    describe "#each_enqueued_command" do
      # we are using defs rather than lets here, because the current implementation
      # does not support calling 'enqueue' on the same command multiple times
      def command1
        described_class.new("command1", subject.version, subject.certname, payload)
      end

      def command2
        described_class.new("command2", subject.version, subject.certname, payload)
      end

      def command3
        described_class.new("command3", subject.version, subject.certname, payload)
      end


      it "should iterate over the commands in the order that they were enqueued" do

        command1.enqueue ; command2.enqueue ; command3.enqueue

        result = []
        described_class.each_enqueued_command { |command| result << command.command }
        result.should == [command1.command, command2.command, command3.command]

        described_class.send(:clear_queue)
        command2.enqueue ; command1.enqueue ; command3.enqueue

        result = []
        described_class.each_enqueued_command { |command| result << command.command }
        result.should == [command2.command, command1.command, command3.command]

        described_class.send(:clear_queue)
        command3.enqueue ; command2.enqueue ; command1.enqueue

        result = []
        described_class.each_enqueued_command { |command| result << command.command }
        result.should == [command3.command, command2.command, command1.command]
      end
    end
  end

  describe "#==" do
    it "should consider two commands equal if all of their properties are equal" do
      command1 = described_class.new(subject.command, subject.version,
                                     subject.certname, payload)
      command2 = described_class.new(subject.command, subject.version,
                                     subject.certname, payload)

      command1.should == subject
      command2.should == subject
      command1.should == command2
    end
  end

  describe "#queue?" do
    it "should return true if the command is queued and false if it is not" do
      subject.queued?.should == false
      subject.enqueue
      subject.queued?.should == true
      subject.dequeue
      subject.queued?.should == false
    end
  end

  describe "queueing commands" do

    describe "#enqueue" do
      it "should write the command to a file and log a message" do
        subject.enqueue
        test_logs.find_all {|m| m =~ /Spooled PuppetDB command.*to file/}.length.should == 1
        path = subject.send(:spool_file_path)
        File.exist?(path).should == true
        spooled_command = described_class.send(:load_command, path)
        spooled_command.should == subject
      end

      context "when the max queue size is exceeded" do
        it "should raise an error" do
          Puppet::Util::Puppetdb.config.expects(:max_queued_commands).at_least_once.returns(0)
          max_queued_commands = Puppet::Util::Puppetdb::config.max_queued_commands
          described_class.expects(:queue_size).returns(1)
          expect {
            subject.enqueue
          }.to raise_error(Puppet::Error, /Unable to queue command.*'#{max_queued_commands}'.*'#{described_class.spool_dir}'/)
        end
      end
    end

    describe "#dequeue" do
      it "should remove the spool file from disk" do
        subject.enqueue
        path = subject.send(:spool_file_path)
        File.exist?(path).should == true
        subject.dequeue
        File.exist?(path).should == false
      end
    end

  end
end
