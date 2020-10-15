require 'spec_helper'
require 'digest/sha1'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb'
require 'uri'
require 'cgi'

describe Puppet::Util::Puppetdb::Command do
  let(:payload) { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }

  let(:subject) { described_class.new("OPEN SESAME", 1,
                                      'foo.localdomain',
                                      Time.now.utc, payload) }


  describe "#submit" do
    let(:http) { mock 'http' }
    before(:each) do
      Puppet::HTTP::Client.expects(:new).returns http
    end

    context "when the submission succeeds" do
      let(:nethttpok) { Net::HTTPOK.new('1.1', 200, '') }
      let(:httpok) { Puppet::HTTP::Response.new(nethttpok, "mock url") }

      it "should issue the HTTP POST and log success" do
        httpok.stubs(:body).returns '{"uuid": "a UUID"}'
        http.expects(:post).with() do | uri, payload, options |
          param_map = CGI::parse(uri.query)
          assert_valid_producer_ts("#{uri.path}?#{uri.query}") &&
            param_map['certname'].first.should == 'foo.localdomain' &&
            param_map['version'].first.should == '1' &&
            param_map['command'].first.should == 'OPEN_SESAME' &&
            options[:options][:compress] == :gzip &&
            options[:options][:metric_id] == [:puppetdb, :command, 'OPEN_SESAME']
        end.returns(httpok)

        subject.submit
        test_logs.find_all { |m|
          m =~ /'#{subject.command}' command for #{subject.certname} submitted to PuppetDB/
        }.length.should == 1
      end
    end

    context "when the submission fails" do
      let(:httpbad) { Net::HTTPBadRequest.new('1.1', 400, '') }

      it "should issue the HTTP POST and raise an exception" do

        httpbad.stubs(:body).returns 'Strange things are afoot'
        http.expects(:post).raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(httpbad, ":(")
        expect {
          subject.submit
        }.to raise_error(Puppet::Error, /Strange things are afoot/)
      end

      it 'when soft_write_failure is enabled should just invoke Puppet.err' do
        subject.expects(:config).at_least_once.returns \
          OpenStruct.new({:soft_write_failure => true})

        httpbad.stubs(:body).returns 'Strange things are afoot'
        http.expects(:post).raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(httpbad, ":(")
        Puppet.expects(:err).with do |msg|
          msg =~ /Strange things are afoot/
        end
        subject.submit
      end
    end
  end

  it "should not warn when the the string contains valid UTF-8 characters" do
    Puppet.expects(:warning).never
    cmd = described_class.new("command-1", 1, "foo.localdomain", Time.now.utc, {"foo" => "\u2192"})
    cmd.payload.include?("\u2192").should be_truthy
  end

  describe "on ruby >= 1.9" do

    it "should warn when a command payload includes non-ascii UTF-8 characters" do
      Puppet.expects(:warning).with {|msg| msg =~ /Error encoding a 'command-1' command for host 'foo.localdomain' ignoring invalid UTF-8 byte sequences/}
      cmd = described_class.new("command-1", 1, "foo.localdomain", Time.now.utc, {"foo" => [192].pack('c*')})
      cmd.payload.include?("\ufffd").should be_truthy
    end

    describe "Debug log testing of bad data" do
      let!(:existing_log_level){ Puppet[:log_level]}

      before :each do
        Puppet[:log_level] = "debug"
      end

      after :each do
        Puppet[:log_level] = "notice"
      end

      it "should warn when a command payload includes non-ascii UTF-8 characters" do
        Puppet.expects(:warning).with do |msg|
          msg =~ /Error encoding a 'command-1' command for host 'foo.localdomain' ignoring invalid UTF-8 byte sequences/
        end
        Puppet.expects(:debug).with do |msg|
          msg =~ /Error encoding a 'command-1' command for host 'foo.localdomain'/ &&
            msg =~ Regexp.new(Regexp.quote('"command":"command-1","version":1,"certname":"foo.localdomain","payload":{"foo"')) &&
            msg =~ /1 invalid\/undefined/
        end
        cmd = described_class.new("command-1", 1, "foo.localdomain", Time.now.utc, {"foo" => [192].pack('c*')})
        cmd.payload.include?("\ufffd").should be_truthy
      end
    end
  end
end
