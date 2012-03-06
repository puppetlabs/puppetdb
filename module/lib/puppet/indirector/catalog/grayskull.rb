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

    data = hash['data']

    add_parameters_if_missing(data)
    add_namevar_aliases(data, catalog)
    munge_edges(data)
    synthesize_edges(data)

    hash
  end

  Relationships = {
    :before    => {:direction => :forward, :relationship => 'before'},
    :require   => {:direction => :reverse, :relationship => 'required-by'},
    :notify    => {:direction => :forward, :relationship => 'notifies'},
    :subscribe => {:direction => :reverse, :relationship => 'subscription-of'},
  }

  def add_parameters_if_missing(hash)
    hash['resources'].each do |resource|
      resource['parameters'] ||= {}
    end

    hash
  end

  def add_namevar_aliases(hash, catalog)
    hash['resources'].each do |resource|
      real_resource = catalog.resource(resource['type'], resource['title'])

      aliases = [real_resource[:alias]].flatten.compact

      name = real_resource[real_resource.send(:namevar)]
      unless name.nil? or real_resource.title == name or aliases.include?(name)
        aliases << name
      end

      resource['parameters']['alias'] = aliases unless aliases.empty?
    end

    hash
  end

  def munge_edges(hash)
    hash['edges'].each do |edge|
      %w[source target].each do |vertex|
        edge[vertex] = resource_ref_to_hash(edge[vertex]) if edge[vertex].is_a?(String)
      end
    end

    hash
  end

  def synthesize_edges(hash)
    hash['resources'].each do |resource|
      next if resource['exported']

      Relationships.each do |param,relation|
        if value = resource['parameters'][param]
          [value].flatten.each do |other_ref|
            edge = {'relationship' => relation[:relationship]}

            resource_hash = {:type => resource['type'], :title => resource['title']}
            other_hash = resource_ref_to_hash(other_ref)

            other_resource = hash['resources'].find {|res| res['type'] == other_hash[:type] and res['title'] == other_hash[:title]}

            raise "Can't find resource #{other_ref} for relationship" unless other_resource

            if other_resource['exported']
              raise "Can't create an edge between #{resource_hash_to_ref(resource_hash)} and exported resource #{other_ref}"
            end

            if relation[:direction] == :forward
              edge.merge!('source' => resource_hash, 'target' => other_hash)
            else
              edge.merge!('source' => other_hash, 'target' => resource_hash)
            end
            hash['edges'] << edge
          end
        end
      end
    end

    hash['edges'].uniq!

    hash
  end

  def resource_ref_to_hash(ref)
    ref =~ /^([^\[\]]+)\[(.+)\]$/m
    {:type => $1, :title => $2}
  end

  def resource_hash_to_ref(hash)
    "#{hash[:type]}[#{hash[:title]}]"
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
