require 'spec_helper'
require 'puppet/util/puppetdb/config'
require 'puppet/util/puppetdb/command_names'

describe Puppet::Util::Puppetdb::Config do
  describe "#load" do
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
      it "should use the default settings" do
        config = described_class.load
        config.server_urls.should == [URI("https://puppetdb:8081")]
        config.submit_only_server_urls.should == []
        config.min_successful_submissions.should == 1
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
server = main-server
   # yet another comment
port = 1234
CONF
        expect { described_class.load }.to_not raise_error
      end


      it "should use the config value if specified" do
        write_config <<CONF
[main]
server_urls = https://main-server:1234
soft_write_failure = true
CONF
        config = described_class.load
        config.server_urls.should == [URI("https://main-server:1234")]
        config.soft_write_failure.should be_truthy
      end

      it "should use the default if no value is specified" do
        write_config ''

        config = described_class.load
        config.server_urls.should == [URI("https://puppetdb:8081")]
        config.soft_write_failure.should be_falsey
      end

      it "should be insensitive to whitespace" do
        write_config <<CONF
[main]
    server_urls = https://main-server:1234
        soft_write_failure = true
CONF
        config = described_class.load
        config.server_urls.should == [URI("https://main-server:1234")]
        config.soft_write_failure.should be_truthy
      end

      it "should accept valid hostnames" do
        write_config <<CONF
[main]
server_urls = https://foo.example-thing.com:8081
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.example-thing.com:8081")]
      end

      it "should raise if a setting is outside of a section" do
        write_config 'foo = bar'

        expect do
          described_class.load
        end.to raise_error(/Setting 'foo = bar' is illegal outside of section/)
      end

      it "should raise if an illegal line is encountered" do
        write_config 'foo bar baz'

        expect do
          described_class.load
        end.to raise_error(/Unparseable line 'foo bar baz'/)
      end

      it "should raise if soft_write_failure and min_successful_submissions are both configured" do
        write_config <<CONF
[main]
server = main-server
port = 1234
soft_write_failure = true
min_successful_submissions = 2
CONF

        expect do
          described_class.load
        end.to raise_error(/soft_write_failure cannot be enabled when min_successful_submissions is greater than 1/)
      end


      it "should accept a single url" do
        write_config <<CONF
[main]
server_urls = https://foo.something-different.com:8080
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.something-different.com:8080")]
      end

      it "should accept multiple urls" do
        write_config <<CONF
[main]
server = foo.example-thing.com
port = 8081
server_urls = https://foo.something-different.com,https://bar.example-thing.com:8989
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.something-different.com"), URI("https://bar.example-thing.com:8989")]
      end

      it "should fail if given an http URL" do
        write_config <<CONF
[main]
server_urls = http://foo.something-different.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/PuppetDB 'server_urls' must be https, found 'http:\/\/foo.something-different.com'/)
      end
      
      it "should fail if given a URL path" do
        uri_string = ""
        write_config <<CONF
[main]
server_urls = https://foo.something-different.com/bar
CONF

        expect do
          config = described_class.load
        end.to raise_error(/PuppetDB 'server_urls' cannot contain URL paths, found 'https:\/\/foo.something-different.com\/bar'/)
      end

      it "should fail if given a server/port combo" do
        write_config <<CONF
[main]
server_urls = foo.com:8080
CONF

        expect do
          config = described_class.load
        end.to raise_error(/PuppetDB 'server_urls' must be https, found 'foo.com:8080'/)
      end

      it "should fail if given a server only" do
        write_config <<CONF
[main]
server_urls = foo.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/PuppetDB 'server_urls' must be https, found 'foo.com'/)
      end


      it "should fail if given an invalid hostname" do
        write_config <<CONF
[main]
server_urls = https://invalid#host#name.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/Error parsing URL 'https:\/\/invalid#host#name.com' in PuppetDB 'server_urls'/)
      end

      it "should fail if given an unparsable second URI" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://invalid#host#name.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/Error parsing URL 'https:\/\/invalid#host#name.com' in PuppetDB 'server_urls'/)
      end

      it "should fail if given an unparsable second URI" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://invalid#host#name.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/Error parsing URL 'https:\/\/invalid#host#name.com' in PuppetDB 'server_urls'/)
      end

      it "should tolerate spaces between URLs" do
        write_config <<CONF
[main]
server_urls = https://foo.something-different.com   ,      https://bar.example-thing.com:8989,    https://baz.example-thing.com:8989
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.something-different.com"),
                                      URI("https://bar.example-thing.com:8989"),
                                      URI("https://baz.example-thing.com:8989")]
      end

      it "should read submit_only_server_urls" do
        write_config <<CONF
[main]
server_urls = https://foo.com
submit_only_server_urls = https://bar.com
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.com")]
        config.submit_only_server_urls.should == [URI("https://bar.com")]
      end

      it "shouldn't allow submit_only_server_urls to overlap with server_urls" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://bar.com
submit_only_server_urls = https://bar.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/Server URLs must be in either server_urls or submit_only_server_urls/)
      end

      it "should read command_broadcast" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://bar.com
command_broadcast = true
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.com"), URI("https://bar.com")]
        config.command_broadcast.should == true
      end

      it "should read min_successful_submissions" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://bar.com
command_broadcast = true
min_successful_submissions = 2
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.com"), URI("https://bar.com")]
        config.command_broadcast.should == true
        config.min_successful_submissions.should == 2
      end

      it "should only allow min_successful_submissions when command_broadcast is set" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://bar.com
min_successful_submissions = 2
CONF
        expect do
          config = described_class.load
        end.to raise_error(/command_broadcast must be set to true to use min_successful_submissions/)
      end

      it "shouldn't allow min_successful_submissions to be greater than the number of server_urls" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://bar.com
command_broadcast = true
min_successful_submissions = 3
CONF
        expect do
          config = described_class.load
        end.to raise_error(/min_successful_submissions \(3\) must be less than or equal to/)
      end

      it "should read sticky_read_failover" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://bar.com
sticky_read_failover = true
CONF

        config = described_class.load
        config.server_urls.should == [URI("https://foo.com"), URI("https://bar.com")]
        config.sticky_read_failover.should == true
      end

    end
  end
end
