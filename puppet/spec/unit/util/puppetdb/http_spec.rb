require 'spec_helper'
require 'puppet/util/puppetdb/http'

describe Puppet::Util::Puppetdb::Http do

  describe "#concat_url_snippets" do
    it "should avoid a double slash" do
      described_class.concat_url_snippets('/foo/', '/bar/').should == '/foo/bar/'
    end
    it "should add a slash if needed" do
      described_class.concat_url_snippets('/foo', 'bar/').should == '/foo/bar/'
      described_class.concat_url_snippets('/foo', 'bar/').should == '/foo/bar/'
    end
    it "should do the right thing if only one snippet has a slash" do
      described_class.concat_url_snippets('/foo', '/bar/').should == '/foo/bar/'
      described_class.concat_url_snippets('/foo/', 'bar/').should == '/foo/bar/'
    end
  end

  before :each do
      Puppet::Util::Puppetdb.stubs(:config).returns config
  end

  let(:config) do
    config = stub 'config'
    config.stubs(:server_urls).returns [URI("https://server1:8080/foo"), URI("https://server2:8181/bar")]
    config.stubs(:server_url_timeout).returns 30
    config.stubs(:server_url_config?).returns false
    config
  end
  let(:http1) {stub 'http'}
  let(:http2) {stub 'http'}


  describe "#action" do
    it "call the proc argument with a correct path" do

      Puppet::Network::HttpPool.expects(:http_instance).returns(http1)
      http1.expects(:get).with("/foo/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

      described_class.action("/bar/baz") do |http_instance, path|
        http_instance.get(path, {})
      end
    end

    it "should not fail over when the first url works" do
      Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
      Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).never

      http1.expects(:get).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

      response = described_class.action("/baz") do |http_instance, path|
        http_instance.get(path, {})
      end

      response.code.should == 200
      response.message.should == 'OK'

    end


    it "fails over to the next url when it can't connect" do
      Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
      Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

      http1.expects(:get).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
      http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

      response = described_class.action("/baz") do |http_instance, path|
        http_instance.get(path, {})
      end

      response.code.should == 200
      response.message.should == 'OK'

    end

    it "fails over to the next url on a server error" do
      Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
      Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

      http1.expects(:get).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
      http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

      response = described_class.action("/baz") do |http_instance, path|
        http_instance.get(path, {})
      end

      response.code.should == 200
      response.message.should == 'OK'
    end

    it "raises an exception when all urls fail" do
      Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
      Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

      http1.expects(:get).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
      http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")


      expect {
         described_class.action("/baz") do |http_instance, path|
           http_instance.get(path, {})
         end
      }.to raise_error Puppet::Error, /Failed to execute/
    end

    it "fails over after IOError" do
      Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
      Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

      http1.expects(:get).with("/foo/baz", {}).raises IOError
      http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

      response = described_class.action("/baz") do |http_instance, path|
        http_instance.get(path, {})
      end

      response.code.should == 200
      response.message.should == 'OK'
    end

    it "times out and rolls to the next url" do
      Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
      Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

      http1.expects(:get).with("/foo/baz", {}).raises Timeout::Error
      http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

      response = described_class.action("/baz") do |http_instance, path|
        http_instance.get(path, {})
      end

      response.code.should == 200
      response.message.should == 'OK'
    end
  end
end
