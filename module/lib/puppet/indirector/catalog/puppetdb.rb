require 'puppet/resource/catalog'
require 'puppet/indirector/rest'
require 'puppet/util/puppetdb'

class Puppet::Resource::Catalog::Puppetdb < Puppet::Indirector::REST
  include Puppet::Util::Puppetdb

  def save(request)
    catalog = munge_catalog(request.instance)

    submit_command(request, catalog, 'replace catalog', 1)
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
      edge['relationship'] ||= 'contains'
    end

    hash
  end

  def map_aliases_to_title(hash)
    aliases = {}
    hash['resources'].each do |resource|
      names = resource['parameters']['alias'] || []
      resource_hash = {'type' => resource['type'], 'title' => resource['title']}
      names.each do |name|
        alias_array = [resource['type'], name]
        aliases[alias_array] = resource_hash
      end
    end
    aliases
  end

  def find_resource(resources, resource_hash)
    return unless resource_hash
    resources.find {|res| res['type'] == resource_hash['type'] and res['title'].to_s == resource_hash['title'].to_s}
  end

  def synthesize_edges(hash)
    aliases = map_aliases_to_title(hash)

    hash['resources'].each do |resource|
      next if resource['exported']

      Relationships.each do |param,relation|
        if value = resource['parameters'][param]
          [value].flatten.each do |other_ref|
            edge = {'relationship' => relation[:relationship]}

            resource_hash = {'type' => resource['type'], 'title' => resource['title']}
            other_hash = resource_ref_to_hash(other_ref)
            other_array = [other_hash['type'], other_hash['title']]

            # Try to find the resource by type/title or look it up as an alias
            # and try that
            other_resource = find_resource(hash['resources'], other_hash) || find_resource(hash['resources'], aliases[other_array])

            raise "Can't find resource #{other_ref} for relationship" unless other_resource

            if other_resource['exported']
              raise "Can't create an edge between #{resource_hash_to_ref(resource_hash)} and exported resource #{other_ref}"
            end

            # If the ref was an alias, it will have a different title, so use
            # that
            other_hash['title'] = other_resource['title']

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
    {'type' => $1, 'title' => $2}
  end

  def resource_hash_to_ref(hash)
    "#{hash['type']}[#{hash['title']}]"
  end
end
