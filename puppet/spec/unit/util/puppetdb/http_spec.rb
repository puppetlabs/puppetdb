require 'spec_helper'
require 'puppet/util/puppetdb/http'

describe Puppet::Util::Puppetdb::Http do

  if Gem::Version.new(Puppet.version) < Gem::Version.new("6.4.0")
    http_expects = :http_instance
  else
    http_expects = :connection
  end

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
    config.stubs(:sticky_read_failover).returns false
    config.stubs(:command_broadcast).returns false
    config.stubs(:min_successful_submissions).returns 1
    config.stubs(:submit_only_server_urls).returns []
    config.stubs(:verify_client_certificate).returns true
    config
  end

  let(:http1) {stub 'http'}
  let(:http2) {stub 'http'}
  let(:http3) {stub 'http'}

  describe "#action" do
    describe "for request_type=:query" do
      it "call the proc argument with a correct path" do
        Puppet::HTTP::Client.expects(:new).returns(http1)
        http1.expects(:get).with do |uri, opts|
          "/foo/bar/baz" == uri.path
        end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

         described_class.action("/bar/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end
       end

       it "should not fail over when the first url works" do
         Puppet::HTTP::Client.expects(:new).returns(http1).once

         http1.expects(:get).with do |uri, opts|
           "/foo/baz" == uri.path
         end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.reason.should == 'OK'
       end

       it "fails over to the next url when it can't connect" do
         Puppet::HTTP::Client.expects(:new).returns(http1)

         http1.expects(:get).with do |uri, opts|
           "/foo/baz" == uri.path
         end.raises Puppet::HTTP::HTTPError, "Connection refused"

         http1.expects(:get).with do |uri, opts|
           "/bar/baz" == uri.path
         end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.reason.should == 'OK'
       end

       it "fails over to the next url on a server error" do
         Puppet::HTTP::Client.expects(:new).returns(http1)

         http1.expects(:get).with do |uri, opts|
           "/foo/baz" == uri.path
         end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")

         http1.expects(:get).with do |uri, opts|
           "/bar/baz" == uri.path
         end.returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "raises an exception when all urls fail" do
         Puppet::HTTP::Client.expects(:new).returns(http1)

         http1.expects(:get).with do |uri, opts|
           "/foo/baz" == uri.path
         end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")
         http1.expects(:get).with do |uri, opts|
           "/bar/baz" == uri.path
         end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")

         expect {
            described_class.action("/baz", :query) do |http_instance, path|
              http_instance.get(path, {})
            end
         }.to raise_error Puppet::Error, /Failed to execute/
       end

       it "fails over after IOError" do
         Puppet::HTTP::Client.expects(:new).returns(http1)

         http1.expects(:get).with do |uri, opts|
           "/foo/baz" == uri.path
         end.raises Puppet::HTTP::HTTPError, "IO Error"
         http1.expects(:get).with do |uri, opts|
           "/bar/baz" == uri.path
         end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.reason.should == 'OK'
       end

       it "times out and rolls to the next url" do
         Puppet::HTTP::Client.expects(:new).returns(http1)

         http1.expects(:get).with do |uri, opts|
           "/foo/baz" == uri.path
         end.raises Timeout::Error
         http1.expects(:get).with do |uri, opts|
           "/bar/baz" == uri.path
         end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.reason.should == 'OK'
       end

       it "doesn't sends queries to hosts in submit_only_server_urls" do
         Puppet::HTTP::Client.expects(:new).returns(http1)

         http1.expects(:get).with do |uri, opts|
           "/foo/baz" == uri.path
         end.raises Puppet::HTTP::HTTPError, "Connection refused"
         http1.expects(:get).with do |uri, opts|
           "/bar/baz" == uri.path
         end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

         config.stubs(:submit_only_server_urls).returns [URI("https://server3:8282/qux")]

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.reason.should == 'OK'
       end

       describe "when sticky_read_failover is false" do
         it "retries the first host after failover" do
           Puppet::HTTP::Client.expects(:new).returns(http1)

           http1.expects(:get).with do |uri, opts|
             "/foo/baz" == uri.path
           end.raises(Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")).twice
           http1.expects(:get).with do |uri, opts|
             "/bar/baz" == uri.path
           end.returns(Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")).twice

           response = described_class.action("/baz", :query) do |http_instance, path|
             http_instance.get(path, {})
           end

           response.code.should == 200
           response.reason.should == 'OK'

           response = described_class.action("/baz", :query) do |http_instance, path|
             http_instance.get(path, {})
           end

           response.code.should == 200
           response.reason.should == 'OK'
         end
       end

       describe "when sticky_read_failover is true" do
        before :each do
          config.stubs(:sticky_read_failover).returns(true)
        end

         it "reuses the same host after failover" do
           Puppet::HTTP::Client.expects(:new).returns(http1)

           http1.expects(:get).with do |uri, opts|
             "/foo/baz" == uri.path
           end.raises(Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")).once
           http1.expects(:get).with do |uri, opts|
             "/bar/baz" == uri.path
           end.returns(Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")).twice

           response = described_class.action("/baz", :query) do |http_instance, path|
             http_instance.get(path, {})
           end

           response.code.should == 200
           response.reason.should == 'OK'

           response = described_class.action("/baz", :query) do |http_instance, path|
             http_instance.get(path, {})
           end

           response.code.should == 200
           response.reason.should == 'OK'
         end
       end

    end

    describe "for request_type=:command" do
      describe "when command_broadcast is true" do
        before :each do
          config.stubs(:command_broadcast).returns(true)
        end

        it "should try to post to all URLs" do
          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end

        it "fails over to the next url when it can't connect" do
          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.raises Puppet::HTTP::HTTPError, "Connection refused"
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end

        it "fails over to the next url when one service is unavailable" do
          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.returns(Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url"))
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end

        it "raises an exception when all urls fail" do
          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")

          expect {
             described_class.action("/baz", :command) do |http_instance, path|
               http_instance.post(path, {})
             end
          }.to raise_error Puppet::Error, /Failed to execute/
        end

        it "raises an exception when min_successful_submissions is not met" do
          config.stubs(:min_successful_submissions).returns 2

          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.raises Puppet::HTTP::HTTPError, "Connection refused"
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

          expect {
             described_class.action("/baz", :command) do |http_instance, path|
               http_instance.post(path, {})
             end
          }.to raise_error Puppet::Error, /Failed to execute/
        end

        it "works when min_successful_submissions is met" do
          config.stubs(:min_successful_submissions).returns 2

          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")


          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end

        it "sends commands to hosts in submit_only_server_urls" do
          config.stubs(:submit_only_server_urls).returns [URI("https://server3:8282/qux")]

          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")
          http1.expects(:post).with.with do |uri, opts|
            "/qux/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end
      end

      describe "when command_broadcast is false" do
        before :each do
          config.stubs(:command_broadcast).returns(false)
        end

        it "should try to post to only the first URL if it succeeds" do
          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.never

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end

        it "fails over to the next url when it can't connect" do
          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.raises Puppet::HTTP::HTTPError, "Connection refused"
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end

        it "raises an exception when all urls fail" do
          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")

          expect {
             described_class.action("/baz", :command) do |http_instance, path|
               http_instance.post(path, {})
             end
          }.to raise_error Puppet::Error, /Failed to execute/
        end

        it "sends commands to hosts in submit_only_server_urls" do
          config.stubs(:submit_only_server_urls).returns [URI("https://server3:8282/qux")]

          Puppet::HTTP::Client.expects(:new).returns(http1)

          http1.expects(:post).with do |uri, opts|
            "/foo/baz" == uri.path
          end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/bar/baz" == uri.path
          end.raises Puppet::HTTP::ResponseError, Puppet::HTTP::Response.new(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable"), "mock url")
          http1.expects(:post).with do |uri, opts|
            "/qux/baz" == uri.path
          end.returns Puppet::HTTP::Response.new(Net::HTTPOK.new('1.1', 200, 'OK'), "mock url")

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.reason.should == 'OK'
        end
      end

    end
  end
end
