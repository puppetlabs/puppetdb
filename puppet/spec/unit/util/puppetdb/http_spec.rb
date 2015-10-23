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
      described_class.reset_query_failover()
  end

  let(:config) do
    config = stub 'config'
    config.stubs(:server_urls).returns [URI("https://server1:8080/foo"), URI("https://server2:8181/bar")]
    config.stubs(:server_url_timeout).returns 30
    config.stubs(:server_url_config?).returns true
    config.stubs(:min_successful_submissions).returns 1
    config.stubs(:submit_only_server_urls).returns []
    config
  end

  let(:http1) {stub 'http'}
  let(:http2) {stub 'http'}
  let(:http3) {stub 'http'}

  describe "#action" do
    describe "for request_type=:query" do
      it "call the proc argument with a correct path" do
         Puppet::Network::HttpPool.expects(:http_instance).returns(http1)
         http1.expects(:get).with("/foo/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         described_class.action("/bar/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end
       end

       it "should not fail over when the first url works" do
         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).never

         http1.expects(:get).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
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

         response = described_class.action("/baz", :query) do |http_instance, path|
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

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "reuses the same host after failover" do
         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1).at_least_once
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2).at_least_once

         http1.expects(:get).with("/foo/baz", {}).returns(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")).once
         http2.expects(:get).with("/bar/baz", {}).returns(Net::HTTPOK.new('1.1', 200, 'OK')).twice

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'

         response = described_class.action("/baz", :query) do |http_instance, path|
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
            described_class.action("/baz", :query) do |http_instance, path|
              http_instance.get(path, {})
            end
         }.to raise_error Puppet::Error, /Failed to execute/
       end

       it "fails over after IOError" do
         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

         http1.expects(:get).with("/foo/baz", {}).raises IOError
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
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

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "doesn't sends queries to hosts in submit_only_server_urls" do
         config.stubs(:submit_only_server_urls).returns [URI("https://server3:8282/qux")]

         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)
         Puppet::Network::HttpPool.expects(:http_instance).with("server3", 8282).never

         http1.expects(:get).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end
    end

    describe "for request_type=:command" do
       it "should try to post to all URLs" do
         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

         http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
         http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :command) do |http_instance, path|
           http_instance.post(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "fails over to the next url when it can't connect" do
         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

         http1.expects(:post).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
         http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :command) do |http_instance, path|
           http_instance.post(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "raises an exception when all urls fail" do
         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

         http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
         http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")

         expect {
            described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end
         }.to raise_error Puppet::Error, /Failed to execute/
       end

       it "raises an exception when min_successful_submissions is not met" do
         config.stubs(:min_successful_submissions).returns 2

         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

         http1.expects(:post).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
         http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         expect {
            described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end
         }.to raise_error Puppet::Error, /Failed to execute/
       end

       it "works when min_successful_submissions is met" do
         config.stubs(:min_successful_submissions).returns 2

         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)

         http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
         http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :command) do |http_instance, path|
           http_instance.post(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "sends commands to hosts in submit_only_server_urls" do
         config.stubs(:submit_only_server_urls).returns [URI("https://server3:8282/qux")]

         Puppet::Network::HttpPool.expects(:http_instance).with("server1", 8080).returns(http1)
         Puppet::Network::HttpPool.expects(:http_instance).with("server2", 8181).returns(http2)
         Puppet::Network::HttpPool.expects(:http_instance).with("server3", 8282).returns(http3)

         http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
         http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
         http3.expects(:post).with("/qux/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :command) do |http_instance, path|
           http_instance.post(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end
    end
  end
end
