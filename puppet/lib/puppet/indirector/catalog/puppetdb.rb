require 'puppet/resource/catalog'
require 'puppet/indirector/rest'
require 'puppet/util/puppetdb'

class Puppet::Resource::Catalog::Puppetdb < Puppet::Indirector::REST
  include Puppet::Util::Puppetdb

  def save(request)
    catalog = munge_catalog(request.instance)

    submit_command(request, catalog, CommandReplaceCatalog, 1)
  end

  def find(request)
    nil
  end

  # TODO: I think that almost everything below this line should be
  #  private, but I don't want to break all the tests right now...

  def munge_catalog(catalog)
    hash = catalog.to_pson_data_hash

    data = hash['data']

    add_parameters_if_missing(data)
    add_namevar_aliases(data, catalog)
    stringify_titles(data)
    sort_unordered_metaparams(data)
    munge_edges(data)
    synthesize_edges(data, catalog)

    hash
  end

  Relationships = {
    :before    => {:direction => :forward, :relationship => 'before'},
    :require   => {:direction => :reverse, :relationship => 'required-by'},
    :notify    => {:direction => :forward, :relationship => 'notifies'},
    :subscribe => {:direction => :reverse, :relationship => 'subscription-of'},
  }

  # Metaparams that may contain arrays, but whose semantics are
  # fundamentally unordered
  UnorderedMetaparams = [:alias, :audit, :before, :check, :notify, :require, :subscribe, :tag]

  def stringify_titles(hash)
    hash['resources'].each do |resource|
      resource['title'] = resource['title'].to_s
    end

    hash
  end

  def add_parameters_if_missing(hash)
    hash['resources'].each do |resource|
      resource['parameters'] ||= {}
    end

    hash
  end

  def add_namevar_aliases(hash, catalog)
    hash['resources'].each do |resource|
      real_resource = catalog.resource(resource['type'], resource['title'])

      # Resources with composite namevars can't be referred to by
      # anything other than their title when declaring
      # relationships. Trying to snag the :alias for these resources
      # will only return _part_ of the name (a problem with Puppet
      # proper), so skipping the adding of aliases for these resources
      # is both an optimization and a safeguard.
      next if real_resource.key_attributes.count > 1

      aliases = [real_resource[:alias]].flatten.compact

      # Non-isomorphic resources aren't unique based on namevar, so we can't
      # use it as an alias
      type = real_resource.resource_type
      if !type.respond_to?(:isomorphic?) or type.isomorphic?
        # This makes me a little sad.  It turns out that the "to_hash" method
        #  of Puppet::Resource can have side effects.  In particular, if the
        #  resource type specifies a title_pattern, calling "to_hash" will trigger
        #  the title_pattern processing, which can have the side effect of
        #  populating the namevar (potentially with a munged value).  Thus,
        #  it is important that we search for namevar aliases in that hash
        #  rather than in the resource itself.
        real_resource_hash = real_resource.to_hash

        name = real_resource_hash[real_resource.send(:namevar)]
        unless name.nil? or real_resource.title == name or aliases.include?(name)
          aliases << name
        end
      end

      # This is pretty bad, but it's possible to have a resource like
      # File[/tmp/foo] which is referred to as File[/tmp/foo/]. Because the
      # relationship isn't evaluated until the agent, and is turned into an
      # actual resource first, this will work, since /tmp/foo/ will be passed
      # through title_patterns, removing the trailing slash. File is the only
      # resource this occurs with, and the only munging is removing the
      # slash. So we special-case file here and add an alias that has a
      # trailing slash.
      if resource['type'] == 'File' and name !~ /\/\Z/
        aliases << name + '/'
      end

      resource['parameters']['alias'] = aliases unless aliases.empty?
    end

    hash
  end

  def sort_unordered_metaparams(hash)
    hash['resources'].each do |resource|
      params = resource['parameters']
      UnorderedMetaparams.each do |metaparam|
        if params[metaparam].kind_of? Array then
          values = params[metaparam].sort
          params[metaparam] = values unless values.empty?
        end
      end
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

  def synthesize_edges(hash, catalog)
    aliases = map_aliases_to_title(hash)

    hash['resources'].each do |resource|
      # Standard virtual resources don't appear in the catalog. However,
      # exported resources which haven't been also collected will appears as
      # exported and virtual (collected ones will only be exported). They will
      # eventually be removed from the catalog, so we can't add edges involving
      # them. Puppet::Resource#to_pson_data_hash omits 'virtual', so we have to
      # look it up in the catalog to find that information. This isn't done in
      # a separate step because we don't actually want to send the field (it
      # will always be false). See ticket #16472.
      #
      # The outer conditional is here because Class[main] can't properly be
      # looked up using catalog.resource and will return nil. See ticket
      # #16473. Yay.
      if real_resource = catalog.resource(resource['type'], resource['title'])
        next if real_resource.virtual?
      end

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

            raise "Can't synthesize edge: #{resource_hash_to_ref(resource_hash)} -#{relation[:relationship]}- #{other_ref} (param #{param}) because #{other_ref} doesn't seem to be in the catalog" unless other_resource

            # As above, virtual exported resources will eventually be removed,
            # so if a real resource refers to one, it's wrong. Non-virtual
            # exported resources are exported resources that were also
            # collected in this catalog, so they're okay. Virtual non-exported
            # resources can't appear in the catalog in the first place, so it
            # suffices to check for virtual.
            if other_real_resource = catalog.resource(other_resource['type'], other_resource['title'])
              if other_real_resource.virtual?
                raise "Can't synthesize edge: #{resource_hash_to_ref(resource_hash)} -#{relation[:relationship]}- #{other_ref} (param #{param}) because #{other_ref} is exported but not collected"
              end
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
