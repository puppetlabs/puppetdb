require 'spec_helper'
require 'digest/sha1'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb'


describe Puppet::Util::Puppetdb::Command do
  let(:payload) { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }
  let(:subject) { described_class.new("OPEN SESAME", 1,
                                      'foo.localdomain', payload) }

  describe "public class methods" do
    describe "#each_queued_command" do
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
        described_class.each_queued_command { |command| result << command.command }
        result.should == [command1.command, command2.command, command3.command]

        described_class.send(:clear_queue)
        command2.enqueue ; command1.enqueue ; command3.enqueue

        result = []
        described_class.each_queued_command { |command| result << command.command }
        result.should == [command2.command, command1.command, command3.command]

        described_class.send(:clear_queue)
        command3.enqueue ; command2.enqueue ; command1.enqueue

        result = []
        described_class.each_queued_command { |command| result << command.command }
        result.should == [command3.command, command2.command, command1.command]
      end
    end


    describe "#retry_queued_commands" do
      let(:payload)        { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }
      let(:good_command1)  { described_class.new("OPEN SESAME", 1, 'foo.localdomain',
                                         payload.merge(:uniqueprop => "good_command1")) }
      let(:good_command2)  { described_class.new("OPEN SESAME", 1, 'bar.localdomain',
                                         payload.merge(:uniqueprop => "good_command2")) }
      let(:bad_command)    { described_class.new("BAD COMMAND", 1, 'foo.localdomain',
                                         payload.merge(:uniqueprop => "bad_command1")) }

      context "when there are no commands queued" do
        it "should not submit any commands, and should log a message" do
          described_class.retry_queued_commands
          described_class.any_instance.expects(:submit).never
          test_logs.find_all { |m| m =~ /No queued commands to retry/ }.length.should == 1
        end
      end

      context "when there are commands queued" do
        context "when the commands can all be submitted successfully" do
          it "should submit and dequeue each command" do
            good_command1.enqueue
            good_command2.enqueue

            # This is a little messy but we need `load_command` to actually return
            # these same Command instances
            described_class.expects(:load_command).with(good_command1.send(:spool_file_path)).once.returns(good_command1)
            described_class.expects(:load_command).with(good_command2.send(:spool_file_path)).once.returns(good_command2)

            good_command1.expects(:submit).once
            good_command2.expects(:submit).once
            described_class.retry_queued_commands
            described_class.queue_size.should == 0
          end
        end

        context "when some of the commands cannot be submitted successfully" do
          it "should submit each command, log failures, and dequeue only the successful commands" do
            good_command1.enqueue
            bad_command.enqueue
            good_command2.enqueue

            # This is a little messy but we need `load_command` to actually return
            # these same Command instances
            described_class.expects(:load_command).with(good_command1.send(:spool_file_path)).once.returns(good_command1)
            described_class.expects(:load_command).with(bad_command.send(:spool_file_path)).once.returns(bad_command)
            described_class.expects(:load_command).with(good_command2.send(:spool_file_path)).never

            good_command1.expects(:submit).once

            bad_command.expects(:submit).once.raises(Puppet::Error, "Strange things are afoot")

            good_command2.expects(:submit).never

            described_class.retry_queued_commands

            test_logs.find_all { |m| m =~ /Failed to submit command to PuppetDB/ }.length.should == 1

            good_command1.queued?.should == false
            bad_command.queued?.should == true
            good_command2.queued?.should == true
          end
        end
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

    describe "#submit" do
      let(:http) { mock 'http' }
      before(:each) do
        Puppet::Network::HttpPool.expects(:http_instance).returns http
      end

      context "when the submission succeeds" do
        let(:httpok) { Net::HTTPOK.new('1.1', 200, '') }

        it "should issue the HTTP POST and log success" do
          httpok.stubs(:body).returns '{"uuid": "a UUID"}'
          http.expects(:post).returns httpok

          subject.submit
          test_logs.find_all { |m|
            m =~ /'#{subject.command}' command for #{subject.certname} submitted to PuppetDB/
          }.length.should == 1
        end
      end

      context "when the submission fails" do
        let(:httpbad) { Net::HTTPBadRequest.new('1.1', 400, '') }

        it "should issue the HTTP POST and raise an error" do

          httpbad.stubs(:body).returns 'Strange things are afoot'
          http.expects(:post).returns httpbad
          expect {
            subject.submit
          }.to raise_error(Puppet::Error, /Strange things are afoot/)
        end
      end
    end

  end
end
