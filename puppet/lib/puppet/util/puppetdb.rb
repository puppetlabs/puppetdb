require 'puppet/util'
require 'digest'

module Puppet::Util::Puppetdb
  def self.server
    @server, @port = load_puppetdb_config unless @server
    @server
  end

  def self.port
    @server, @port = load_puppetdb_config unless @port
    @port
  end

  module ClassMethods
    def server
      Puppet::Util::Puppetdb.server
    end

    def port
      Puppet::Util::Puppetdb.port
    end
  end

  def self.included(child)
    child.extend ClassMethods
  end

  def submit_command(request, command_payload, command, version)
    message = format_command(command_payload, command, version)

    checksum = Digest::SHA1.hexdigest(message)

    payload = CGI.escape(message)

    for_whom = " for #{request.key}" if request.key

    begin
      response = http_post(request, "/commands", "checksum=#{checksum}&payload=#{payload}", headers)

      if response.is_a? Net::HTTPSuccess
        result = PSON.parse(response.body)
        Puppet.info "'#{command}' command#{for_whom} submitted to PuppetDB with UUID #{result['uuid']}"
        result
      else
        # Newline characters cause an HTTP error, so strip them
        raise "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
      end
    rescue => e
      raise Puppet::Error, "Failed to submit '#{command}' command#{for_whom} to PuppetDB at #{self.class.server}:#{self.class.port}: #{e}"
    end
  end

  def format_command(payload, command, version)
    message = {
      :command => command,
      :version => version,
      :payload => payload.to_pson,
    }.to_pson

    utf8_string(message)
  end

  def utf8_string(str)
    # Ruby 1.8 doesn't have String#encode, and String#encode('UTF-8') on an
    # invalid UTF-8 string will leave the invalid byte sequences, so we have
    # to use iconv in both of those cases.
    if RUBY_VERSION =~ /1.8/ or str.encoding == Encoding::UTF_8
      iconv_to_utf8(str)
    else
      begin
        str.encode('UTF-8')
      rescue Encoding::InvalidByteSequenceError, Encoding::UndefinedConversionError => e
        # If we got an exception, the string is either invalid or not
        # convertible to UTF-8, so drop those bytes.
        Puppet.warning "Ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB"
        str.encode('UTF-8', :invalid => :replace, :undef => :replace)
      end
    end
  end

  def iconv_to_utf8(str)
    iconv = Iconv.new('UTF-8//IGNORE', 'UTF-8')

    # http://po-ru.com/diary/fixing-invalid-utf-8-in-ruby-revisited/
    converted_str = iconv.iconv(str + " ")[0..-2]
    if converted_str != str
      Puppet.warning "Ignoring invalid UTF-8 byte sequences in data to be sent to PuppetDB"
    end
    converted_str
  end

  private

  def self.load_puppetdb_config
    default_server = "puppetdb"
    default_port = 8080

    require 'puppet/util/inifile'

    config = File.join(Puppet[:confdir], "puppetdb.conf")

    if File.exists?(config)
      Puppet.debug("Configuring PuppetDB terminuses with config file #{config}")
    else
      Puppet.debug("No puppetdb.conf file found; falling back to default #{default_server}:#{default_port}")
    end

    ini = Puppet::Util::IniConfig::File.new
    ini.read(config)

    main_section = ini[:main] || {}
    server = main_section['server'] || default_server
    port = main_section['port'] || default_port

    [server.strip, port.to_i]
  rescue => detail
    puts detail.backtrace if Puppet[:trace]
    Puppet.warning "Could not configure PuppetDB terminuses: #{detail}"
    raise
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end
end
