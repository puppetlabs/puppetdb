#!/usr/bin/env rspec
# encoding: UTF-8

require 'spec_helper'
require 'digest/sha1'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'

Command                 = Puppet::Util::Puppetdb::Command


describe Puppet::Util::Puppetdb do
  subject { Object.new.extend described_class }

  describe "spooling commands" do

    let(:command_dir)    { subject.send(:command_dir) }
    let(:payload)        { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }
    let(:good_command1)  { Command.new("OPEN SESAME", 1, 'foo.localdomain',
                                       payload.merge(:uniqueprop => "good_command1")) }
    let(:good_command2)  { Command.new("OPEN SESAME", 1, 'bar.localdomain',
                                       payload.merge(:uniqueprop => "good_command2")) }
    let(:bad_command)    { Command.new("BAD COMMAND", 1, 'foo.localdomain',
                                       payload.merge(:uniqueprop => "bad_command1")) }

    describe "#flush_commands" do
      context "when there are no commands queued" do
        it "should do nothing, log nothing" do
          subject.send(:flush_commands)
          subject.expects(:submit_single_command).never
          test_logs.length.should == 0
        end
      end

      context "when there are commands queued" do
        context "when the commands can all be submitted successfully" do
          it "should submit and dequeue each command" do
            good_command1.enqueue
            good_command2.enqueue
            subject.expects(:submit_single_command).times(2)
            subject.send(:flush_commands)
            num_remaining = 0
            Puppet::Util::Puppetdb::Command.each_enqueued_command do |c|
              num_remaining += 1
            end
            num_remaining.should == 0
          end
        end

        context "when some of the commands cannot be submitted successfully" do
          let(:subject) {
            class TestClass
              include Puppet::Util::Puppetdb

              attr_reader :num_commands_submitted

              # I wish this test wasn't so tightly coupled with the implementation
              # details, but I need a way to make some commands "fail" and others
              # succeed.
              def submit_single_command(command)
                @num_commands_submitted ||= 0
                @num_commands_submitted += 1
                if (command.command == "BAD COMMAND")
                  raise Puppet::Error("Strange things are afoot")
                end
              end

            end

            TestClass.new
          }

          it "should submit each command, log failures, and dequeue only the successful commands" do
            good_command1.enqueue
            bad_command.enqueue
            good_command2.enqueue

            subject.send(:flush_commands)
            subject.num_commands_submitted.should == 2

            test_logs.find_all { |m| m =~ /Failed to submit command to PuppetDB/ }.length.should == 1

            good_command1.queued?.should == false
            bad_command.queued?.should == true
            good_command2.queued?.should == true
          end
        end
      end
    end

    describe "#submit_single_command" do
      context "when the submission succeeds" do
        let(:httpok) { Net::HTTPOK.new('1.1', 200, '') }

        it "should issue the HTTP POST and log success" do
          httpok.stubs(:body).returns '{"uuid": "a UUID"}'
          subject.expects(:http_post).returns(httpok)
          subject.send(:submit_single_command, good_command1)
          test_logs.find_all { |m|
            m =~ /'#{good_command1.command}' command for #{good_command1.certname} submitted to PuppetDB/
          }.length.should == 1
        end
      end

      context "when the submission fails" do
        let(:httpbad) { Net::HTTPBadRequest.new('1.1', 400, '') }

        it "should issue the HTTP POST and raise an error" do
          httpbad.stubs(:body).returns 'Strange things are afoot'
          subject.expects(:http_post).returns(httpbad)
          expect {
            subject.send(:submit_single_command, good_command1)
          }.to raise_error(Puppet::Error, /Strange things are afoot/)
        end
      end
    end

    describe "#submit_command" do
      context "when the command is submitted successfully" do
        it "should flush the queue and then submit the command" do
          subject.expects(:flush_commands).once
          subject.expects(:submit_single_command).once
          subject.submit_command(good_command1.certname,
                                 good_command1.payload,
                                 good_command1.command,
                                 good_command1.version)
        end
      end

      context "when the command is not submitted successfully" do
        context "when the command does not support queueing" do
          it "should not try to enqueue the command" do
            subject.expects(:flush_commands).once
            subject.expects(:submit_single_command).once.raises(Puppet::Error, "Strange things are afoot")

            # careful here... since we're going to stub Command.new, we need to
            # make sure we reference good_command1 first, because it calls Command.new.
            good_command1.expects(:supports_queueing?).returns(false)
            good_command1.expects(:enqueue).never
            Command.expects(:new).once.returns(good_command1)

            subject.submit_command(good_command1.certname,
                                   good_command1.payload,
                                   good_command1.command,
                                   good_command1.version)
          end
        end

        context "when the command does support queueing" do
          it "should enqueue the command" do
            subject.expects(:flush_commands).once
            subject.expects(:submit_single_command).once.raises(Puppet::Error, "Strange things are afoot")

            # careful here... since we're going to stub Command.new, we need to
            # make sure we reference good_command1 first, because it calls Command.new.
            good_command1.expects(:supports_queueing?).returns(true)
            good_command1.expects(:enqueue).once
            Command.expects(:new).once.returns(good_command1)

            subject.submit_command(good_command1.certname,
                                   good_command1.payload,
                                   good_command1.command,
                                   good_command1.version)
          end
        end

      end

    end
  end

end
