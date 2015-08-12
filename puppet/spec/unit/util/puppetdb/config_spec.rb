require 'spec_helper'
require 'puppet/util/puppetdb/config'
require 'puppet/util/puppetdb/command_names'

# Create a local copy of these constants so that we don't have to refer to them
# by their full namespaced name
CommandReplaceCatalog   = Puppet::Util::Puppetdb::CommandNames::CommandReplaceCatalog
CommandReplaceFacts     = Puppet::Util::Puppetdb::CommandNames::CommandReplaceFacts
CommandStoreReport      = Puppet::Util::Puppetdb::CommandNames::CommandStoreReport

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
server = main-server
port = 1234
soft_write_failure = true
CONF
        config = described_class.load
        config.server_urls.should == [URI("https://main-server:1234")]
        config.soft_write_failure.should be_true
      end

      it "should use the default if no value is specified" do
        write_config ''

        config = described_class.load
        config.server_urls.should == [URI("https://puppetdb:8081")]
        config.soft_write_failure.should be_false
      end

      it "should be insensitive to whitespace" do
        write_config <<CONF
[main]
  server = main-server
    port    =  1234
CONF
        config = described_class.load
        config.server_urls.should == [URI("https://main-server:1234")]
      end

      it "should accept valid hostnames" do
        write_config <<CONF
[main]
server = foo.example-thing.com
port = 8081
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
server_urls = https://invalid_host_name.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/Error parsing URL 'https:\/\/invalid_host_name.com' in PuppetDB 'server_urls'/)
      end

      it "should fail if given an unparsable second URI" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://invalid_host_name.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/Error parsing URL 'https:\/\/invalid_host_name.com' in PuppetDB 'server_urls'/)
      end

      it "should fail if given an unparsable second URI" do
        write_config <<CONF
[main]
server_urls = https://foo.com,https://invalid_host_name.com
CONF

        expect do
          config = described_class.load
        end.to raise_error(/Error parsing URL 'https:\/\/invalid_host_name.com' in PuppetDB 'server_urls'/)
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
    end
  end
end
