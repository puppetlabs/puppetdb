#!/usr/bin/env rspec
# encoding: UTF-8

require 'spec_helper'
require 'digest/sha1'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'

# Create a local copy of these constants so that we don't have to refer to them
# by their full namespaced name
CommandReplaceCatalog   = Puppet::Util::Puppetdb::CommandNames::CommandReplaceCatalog
CommandReplaceFacts     = Puppet::Util::Puppetdb::CommandNames::CommandReplaceFacts
CommandStoreReport      = Puppet::Util::Puppetdb::CommandNames::CommandStoreReport

Command                 = Puppet::Util::Puppetdb::Command


describe Puppet::Util::Puppetdb do
  subject { Object.new.extend described_class }

  describe "#load_puppetdb_config" do
    let(:confdir) do
      temp = Tempfile.new('confdir')
      path = temp.path
      temp.close!
      Dir.mkdir(path)
      path
    end

    before :each do
      Puppet[:confdir] = confdir
    end

    after :each do
      FileUtils.rm_rf(confdir)
    end

    describe "with no config file" do
      it "should use the default server and port" do
        config = described_class.load_puppetdb_config
        config[:server].should == 'puppetdb'
        config[:port].should == 8081
      end

      it "should use the default settings for command spooling" do
        config = described_class.load_puppetdb_config
        config[CommandReplaceCatalog][:spool].should == false
        config[CommandReplaceFacts][:spool].should == false
        config[CommandStoreReport][:spool].should == true
      end
    end

    describe "with a config file" do
      def write_config(content)
        conf = File.join(confdir, 'puppetdb.conf')
        File.open(conf, 'w') { |file| file.print(content) }
      end

      it "should not explode if there are comments in the config file" do
        write_config <<CONF
#this is a comment
 ; so is this
[main]
server = main_server
   # yet another comment
port = 1234
CONF
        expect { described_class.load_puppetdb_config }.to_not raise_error
      end


      it "should use the config value if specified" do
        write_config <<CONF
[main]
server = main_server
port = 1234
CONF
        config = described_class.load_puppetdb_config
        config[:server].should == 'main_server'
        config[:port].should == 1234
      end

      it "should use the default if no value is specified" do
        write_config ''

        config = described_class.load_puppetdb_config
        config[:server].should == 'puppetdb'
        config[:port].should == 8081
      end

      it "should be insensitive to whitespace" do
        write_config <<CONF
[main]
    server = main_server
      port    =  1234
CONF
        config = described_class.load_puppetdb_config
        config[:server].should == 'main_server'
        config[:port].should == 1234
      end

      it "should accept valid hostnames" do
        write_config <<CONF
[main]
server = foo.example-thing.com
port = 8081
CONF

        config = described_class.load_puppetdb_config
        config[:server].should == 'foo.example-thing.com'
        config[:port].should == 8081
      end

      it "should raise if a setting is outside of a section" do
        write_config 'foo = bar'

        expect do
          described_class.load_puppetdb_config
        end.to raise_error(/Setting 'foo = bar' is illegal outside of section/)
      end

      it "should raise if an illegal line is encountered" do
        write_config 'foo bar baz'

        expect do
          described_class.load_puppetdb_config
        end.to raise_error(/Unparseable line 'foo bar baz'/)
      end

      context "command-specific settings" do
        it "should use the defaults if no value is specified" do
          write_config ''

          config = described_class.load_puppetdb_config
          config[CommandReplaceCatalog][:spool].should == false
          config[CommandReplaceFacts][:spool].should == false
          config[CommandStoreReport][:spool].should == true
        end

        it "should use the config value if specified" do
          write_config <<CONF
[facts]
spool = true

[catalogs]
spool = true

[reports]
spool = false
CONF
          config = described_class.load_puppetdb_config
          config[CommandReplaceCatalog][:spool].should == true
          config[CommandReplaceFacts][:spool].should == true
          config[CommandStoreReport][:spool].should == false
        end
      end

    end
  end

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
            good_command2.enqueue
            bad_command.enqueue

            # More coupling with implementation details, yuck.  However, it's
            # important here that I know that the failed command will be processed
            # before at least one 'good' command, to ensure that the bad
            # command doesn't prevent us from continuing processing.
            Puppet::Util::Puppetdb::Command.stubs(:each_enqueued_command).
                multiple_yields(bad_command, good_command1, good_command2)

            subject.send(:flush_commands)
            subject.num_commands_submitted.should == 3

            test_logs.find_all { |m| m =~ /Failed to submit command to PuppetDB/ }.length.should == 1

            good_command1.queued?.should == false
            good_command2.queued?.should == false
            bad_command.queued?.should == true
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
      context "when the command is set to spool" do
        it "should enqueue the command and then flush" do
          subject.expects(:command_spooled?).returns(true)
          # careful here... since we're going to stub Command.new, we need to
          # make sure we reference good_command1 first, because it calls Command.new.
          good_command1.expects(:enqueue).once
          Command.expects(:new).once.returns(good_command1)
          subject.expects(:flush_commands).once
          subject.expects(:submit_single_command).never
          subject.submit_command(good_command1.certname,
                                 good_command1.payload,
                                 good_command1.command,
                                 good_command1.version)
        end
      end
      context "when the command is set *not* to spool" do
        it "should simply submit the command, and not enqueue or flush" do
          subject.expects(:command_spooled?).returns(false)
          subject.expects(:enqueue_command).never
          subject.expects(:flush_commands).never
          subject.expects(:submit_single_command).once
          subject.submit_command(good_command1.certname,
                                 good_command1.payload,
                                 good_command1.command,
                                 good_command1.version)
        end
      end
    end
  end

end
