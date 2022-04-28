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
      described_class.class_variable_set(:@@submit_only_url_timeouts, {})
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
         Puppet::Network::HttpPool.expects(http_expects).returns(http1)
         http1.expects(:get).with("/foo/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         described_class.action("/bar/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end
       end

       it "should not fail over when the first url works" do
         Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
         Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).never

         http1.expects(:get).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "fails over to the next url when it can't connect" do
         Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
         Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

         http1.expects(:get).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "fails over to the next url on a server error" do
         Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
         Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

         http1.expects(:get).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "raises an exception when all urls fail" do
         Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
         Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

         http1.expects(:get).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")

         expect {
            described_class.action("/baz", :query) do |http_instance, path|
              http_instance.get(path, {})
            end
         }.to raise_error Puppet::Error, /Failed to execute/
       end

       it "fails over after IOError" do
         Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
         Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

         http1.expects(:get).with("/foo/baz", {}).raises IOError
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "times out and rolls to the next url" do
         Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
         Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

         http1.expects(:get).with("/foo/baz", {}).raises Timeout::Error
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       it "doesn't send queries to hosts in submit_only_server_urls" do
         config.stubs(:submit_only_server_urls).returns [URI("https://server3:8282/qux")]

         Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
         Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)
         Puppet::Network::HttpPool.expects(http_expects).with("server3", 8282, anything).never

         http1.expects(:get).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
         http2.expects(:get).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

         response = described_class.action("/baz", :query) do |http_instance, path|
           http_instance.get(path, {})
         end

         response.code.should == 200
         response.message.should == 'OK'
       end

       describe "when sticky_read_failover is false" do
         it "retries the first host after failover" do
           Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1).at_least_once
           Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2).at_least_once

           http1.expects(:get).with("/foo/baz", {}).returns(Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")).twice
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
       end

       describe "when sticky_read_failover is true" do
        before :each do
          config.stubs(:sticky_read_failover).returns(true)
        end

         it "reuses the same host after failover" do
           Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1).at_least_once
           Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2).at_least_once

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
       end

    end

    describe "for request_type=:command" do
      describe "when command_broadcast is true" do
        before :each do
          config.stubs(:command_broadcast).returns(true)
        end

        it "should try to post to all URLs" do
          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

          http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
          http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.message.should == 'OK'
        end

        it "fails over to the next url when it can't connect" do
          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

          http1.expects(:post).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
          http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.message.should == 'OK'
        end

        it "fails over to the next url when one service is unavailable" do
          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

          http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
          http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.message.should == 'OK'
        end

        it "raises an exception when all urls fail" do
          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

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

          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

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

          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

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

          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)
          Puppet::Network::HttpPool.expects(http_expects).with("server3", 8282, anything).returns(http3)

          http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
          http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
          http3.expects(:post).with("/qux/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.message.should == 'OK'
        end

        context "tracking timeouts for `submit_only` urls" do
          it "tracks timeouts for `submit_only` urls" do
            submit_only_url = URI("https://server3:8282/qux")
            config.stubs(:submit_only_server_urls).returns [submit_only_url]

            Puppet::Network::HttpPool.expects(http_expects).times(3).with("server1", 8080, anything).returns(http1)
            Puppet::Network::HttpPool.expects(http_expects).times(3).with("server2", 8181, anything).returns(http2)
            Puppet::Network::HttpPool.expects(http_expects).twice.with("server3", 8282, anything).returns(http3)

            http1.expects(:post).with("/foo/baz", {}).times(3).returns Net::HTTPOK.new('1.1', 200, 'OK')
            http2.expects(:post).with("/bar/baz", {}).times(3).returns Net::HTTPOK.new('1.1', 200, 'OK')
            # By the third request, this url will no longer bit hit
            http3.expects(:post).with("/qux/baz", {}).twice.raises Timeout::Error


            # First request with timing out submit_only url
            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == true
            # Url has not been removed yet, even though it has timed out once
            config.submit_only_server_urls.should == [submit_only_url]
            response.code.should == 200
            response.message.should == 'OK'

            # Second request with timing out submit_only url
            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == false
            # Timed out twice, so now the URL is removed so we don't keep trying it
            config.submit_only_server_urls.should == []
            response.code.should == 200
            response.message.should == 'OK'

            # Third request with `submit_only` url removed
            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            response.code.should == 200
            response.message.should == 'OK'
          end

          it "does not remove the url if it only times out once" do
            submit_only_url = URI("https://server3:8282/qux")
            config.stubs(:submit_only_server_urls).returns [submit_only_url]

            Puppet::Network::HttpPool.expects(http_expects).twice.with("server1", 8080, anything).returns(http1)
            Puppet::Network::HttpPool.expects(http_expects).twice.with("server2", 8181, anything).returns(http2)
            Puppet::Network::HttpPool.expects(http_expects).twice.with("server3", 8282, anything).returns(http3)

            http1.expects(:post).with("/foo/baz", {}).twice.returns Net::HTTPOK.new('1.1', 200, 'OK')
            http2.expects(:post).with("/bar/baz", {}).twice.returns Net::HTTPOK.new('1.1', 200, 'OK')
            http3.expects(:post).with("/qux/baz", {}).twice.raises(Timeout::Error).then.returns(Net::HTTPOK.new('1.1', 200, 'OK'))

            # First request with timing out submit_only url
            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == true
            # Url has not been removed yet, even though it has timed out once
            config.submit_only_server_urls.should == [submit_only_url]
            response.code.should == 200
            response.message.should == 'OK'

            # Second request with succeeding submit_only url
            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == false
            # Did not time out again, so we do not remove the url
            config.submit_only_server_urls.should == [submit_only_url]
            response.code.should == 200
            response.message.should == 'OK'
          end

          it "does not track timeouts for non-submit_only urls" do
            submit_only_url = URI("https://server3:8282/qux")
            config.stubs(:submit_only_server_urls).returns [submit_only_url]

            Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
            Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)
            Puppet::Network::HttpPool.expects(http_expects).with("server3", 8282, anything).returns(http3)

            http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
            http2.expects(:post).with("/bar/baz", {}).raises Timeout::Error
            http3.expects(:post).with("/qux/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            # We set the submit_only url entry to false (one entry), and do nothing for the regular URL
            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == false
            described_class.class_variable_get(:@@submit_only_url_timeouts).count.should == 1

            response.code.should == 200
            response.message.should == 'OK'
          end
        end
      end

      describe "when command_broadcast is false" do
        before :each do
          config.stubs(:command_broadcast).returns(false)
        end

        it "should try to post to only the first URL if it succeeds" do
          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).never

          http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')
          http2.expects(:post).with("/bar/baz", {}).never

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.message.should == 'OK'
        end

        it "fails over to the next url when it can't connect" do
          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

          http1.expects(:post).with("/foo/baz", {}).raises SystemCallError, "Connection refused"
          http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.message.should == 'OK'
        end

        it "raises an exception when all urls fail" do
          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)

          http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
          http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")

          expect {
             described_class.action("/baz", :command) do |http_instance, path|
               http_instance.post(path, {})
             end
          }.to raise_error Puppet::Error, /Failed to execute/
        end

        it "sends commands to hosts in submit_only_server_urls" do
          config.stubs(:submit_only_server_urls).returns [URI("https://server3:8282/qux")]

          Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
          Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)
          Puppet::Network::HttpPool.expects(http_expects).with("server3", 8282, anything).returns(http3)

          http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
          http2.expects(:post).with("/bar/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
          http3.expects(:post).with("/qux/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

          response = described_class.action("/baz", :command) do |http_instance, path|
            http_instance.post(path, {})
          end

          response.code.should == 200
          response.message.should == 'OK'
        end

        context "tracking timeouts for `submit_only` urls" do
          it "tracks timeouts for `submit_only` urls" do
            submit_only_url = URI("https://server3:8282/qux")
            config.stubs(:submit_only_server_urls).returns [submit_only_url]

            Puppet::Network::HttpPool.expects(http_expects).times(3).with("server1", 8080, anything).returns(http1)
            Puppet::Network::HttpPool.expects(http_expects).times(3).with("server2", 8181, anything).returns(http2)
            Puppet::Network::HttpPool.expects(http_expects).twice.with("server3", 8282, anything).returns(http3)

            http1.expects(:post).with("/foo/baz", {}).times(3).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
            http2.expects(:post).with("/bar/baz", {}).times(3).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
            # By the third request, this url will no longer be hit
            http3.expects(:post).with("/qux/baz", {}).twice.raises Timeout::Error


            # First request with timing out submit_only url
            # All requests fail here, so the action will raise
            expect {
              described_class.action("/baz", :command) do |http_instance, path|
                http_instance.post(path, {})
              end
            }.to raise_error(Puppet::Error)

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == true
            # Url has not been removed yet, even though it has timed out once
            config.submit_only_server_urls.should == [submit_only_url]

            # Second request with timing out submit_only url
            # All requests fail here, so the action will raise
            expect {
              described_class.action("/baz", :command) do |http_instance, path|
                http_instance.post(path, {})
              end
            }.to raise_error(Puppet::Error)

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == false
            # Timed out twice, so now the URL is removed so we don't keep trying it
            config.submit_only_server_urls.should == []

            # Third request with `submit_only` url removed
            # All requests fail here, so the action will raise
            expect {
              described_class.action("/baz", :command) do |http_instance, path|
                http_instance.post(path, {})
              end
            }.to raise_error(Puppet::Error)
          end

          it "does not remove the url if it only times out once" do
            submit_only_url = URI("https://server3:8282/qux")
            config.stubs(:submit_only_server_urls).returns [submit_only_url]

            Puppet::Network::HttpPool.expects(http_expects).twice.with("server1", 8080, anything).returns(http1)
            Puppet::Network::HttpPool.expects(http_expects).twice.with("server2", 8181, anything).returns(http2)
            Puppet::Network::HttpPool.expects(http_expects).twice.with("server3", 8282, anything).returns(http3)

            http1.expects(:post).with("/foo/baz", {}).twice.returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
            http2.expects(:post).with("/bar/baz", {}).twice.returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
            http3.expects(:post).with("/qux/baz", {}).twice.raises(Timeout::Error).then.returns(Net::HTTPOK.new('1.1', 200, 'OK'))

            # First request with timing out submit_only url
            # All requests fail here, so the action will raise
            expect {
              described_class.action("/baz", :command) do |http_instance, path|
                http_instance.post(path, {})
              end
            }.to raise_error(Puppet::Error)

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == true
            # Url has not been removed yet, even though it has timed out once
            config.submit_only_server_urls.should == [submit_only_url]

            # Second request with succeeding submit_only url
            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == false
            # Did not time out again, so we do not remove the url
            config.submit_only_server_urls.should == [submit_only_url]
            response.code.should == 200
            response.message.should == 'OK'
          end

          it "does not track timeouts for non-submit_only urls" do
            submit_only_url = URI("https://server3:8282/qux")
            config.stubs(:submit_only_server_urls).returns [submit_only_url]

            Puppet::Network::HttpPool.expects(http_expects).with("server1", 8080, anything).returns(http1)
            Puppet::Network::HttpPool.expects(http_expects).with("server2", 8181, anything).returns(http2)
            Puppet::Network::HttpPool.expects(http_expects).with("server3", 8282, anything).returns(http3)

            http1.expects(:post).with("/foo/baz", {}).returns Net::HTTPServiceUnavailable.new('1.1', 503, "Unavailable")
            http2.expects(:post).with("/bar/baz", {}).raises Timeout::Error
            http3.expects(:post).with("/qux/baz", {}).returns Net::HTTPOK.new('1.1', 200, 'OK')

            response = described_class.action("/baz", :command) do |http_instance, path|
              http_instance.post(path, {})
            end

            # We set the submit_only url entry to false (one entry), and do nothing for the regular URL
            described_class.class_variable_get(:@@submit_only_url_timeouts)[submit_only_url].should == false
            described_class.class_variable_get(:@@submit_only_url_timeouts).count.should == 1

            response.code.should == 200
            response.message.should == 'OK'
          end
        end
      end

    end
  end
end
