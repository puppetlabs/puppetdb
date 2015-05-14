require 'spec_helper'
require 'digest/sha1'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb'


describe Puppet::Util::Puppetdb::Command do
  let(:payload) { {'resistance' =>  'futile', 'opinion' => 'irrelevant'} }
  let(:subject) { described_class.new("OPEN SESAME", 1,
                                      'foo.localdomain', payload) }


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

      it "should issue the HTTP POST and raise an exception" do

        httpbad.stubs(:body).returns 'Strange things are afoot'
        http.expects(:post).returns httpbad
        expect {
          subject.submit
        }.to raise_error(Puppet::Error, /Strange things are afoot/)
      end

      it 'when soft_write_failure is enabled should just invoke Puppet.err' do
        subject.expects(:config).at_least_once.returns \
          OpenStruct.new({:soft_write_failure => true})

        httpbad.stubs(:body).returns 'Strange things are afoot'
        http.expects(:post).returns httpbad
        Puppet.expects(:err).with do |msg|
          msg =~ /Strange things are afoot/
        end
        subject.submit
      end
    end

  end

end
