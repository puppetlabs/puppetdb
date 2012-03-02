require 'puppet/resource/catalog'
require 'puppet/indirector/rest'
require 'digest'

class Puppet::Resource::Catalog::Grayskull < Puppet::Indirector::REST
  # These settings don't exist in Puppet yet, so we have to hard-code them for now.
  #use_server_setting :grayskull_server
  #use_port_setting :grayskull_port

  def self.server
    "grayskull"
  end

  def self.port
    8080
  end

  # These methods don't really belong here, but this is the best we can do to
  # share them inside a module.
  def self.utf8_string(str)
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
        Puppet.warning "Ignoring invalid UTF-8 byte sequences in data to be sent to Grayskull"
        str.encode('UTF-8', :invalid => :replace, :undef => :replace)
      end
    end
  end

  def self.iconv_to_utf8(str)
    iconv = Iconv.new('UTF-8//IGNORE', 'UTF-8')

    # http://po-ru.com/diary/fixing-invalid-utf-8-in-ruby-revisited/
    converted_str = iconv.iconv(str + " ")[0..-2]
    if converted_str != str
      Puppet.warning "Ignoring invalid UTF-8 byte sequences in data to be sent to Grayskull"
    end
    converted_str
  end

  def save(request)
    catalog = munge_catalog(request.instance)

    msg = self.class.utf8_string(message(catalog).to_pson)

    checksum = Digest::SHA1.hexdigest(msg)
    payload = CGI.escape(msg)

    http_post(request, "/commands", "checksum=#{checksum}&payload=#{payload}", headers)
  end

  def find(request)
    nil
  end

  def munge_catalog(catalog)
    hash = catalog.to_pson_data_hash

    hash['data']['resources'].each do |resource|
      next unless resource['parameters']

      real_resource = catalog.resource(resource['type'], resource['title'])

      aliases = real_resource[:alias]

      case aliases
      when String
        aliases = [aliases]
      when nil
        aliases = []
      end

      name = real_resource[real_resource.send(:namevar)]
      unless name.nil? or real_resource.title == name or aliases.include?(name)
        aliases << name
      end

      resource['parameters']['alias'] = aliases
    end

    hash
  end

  def headers
    {
      "Accept" => "application/json",
      "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end

  def message(catalog)
    {
      :command => "replace catalog",
      :version => 1,
      :payload => catalog.to_pson,
    }
  end
end
