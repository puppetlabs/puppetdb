require 'puppet/face'

Puppet::Face.define(:storeconfigs, '0.0.1') do
  copyright "Puppet Labs", 2011
  license   "Apache 2 license"

  summary "Interact with the storeconfigs database"
  description <<-DESC
    This subcommand interacts with the ActiveRecord storeconfigs database, and
    can be used to export a dump of that data which is suitable for import by
    PuppetDB.
  DESC

  action :export do
    summary "Export the storeconfigs database"
    arguments "<filename>"
    description <<-DESC
      Generate a complete dump of all catalogs from the storeconfigs database,
      in a format which can be consumed by PuppetDB. Returns the set of nodes
      which were exported.
    DESC

    when_invoked do |filename, options|
      require 'puppet/rails'

      Puppet::Rails.connect

      # Fetch all nodes, including exported resources and their params
      nodes = Puppet::Rails::Host.all(:include => {:resources => [:param_values, :puppet_tags]},
                                      :conditions => {:resources => {:exported => true}})

      catalogs = nodes.map {|node| node_to_catalog_hash(node)}

      File.open(filename, 'w') do |file|
        # The content of this file is a series of individual catalogs as JSON
        # objects, not an array of them. This makes it easier to, for instance,
        # process them in a stream.
        catalogs.each do |catalog|
          file.puts catalog.to_pson
        end
      end

      nodes.map(&:name).sort
    end

    when_rendering :console do |nodes|
      "Exported #{nodes.length} nodes"
    end
  end

  def node_to_catalog_hash(node)
    resources = node.resources.map {|resource| resource_to_hash(resource)}

    {
      :metadata => {
        :api_version => 1,
      },
      :data => {
        :name => node.name,
        :version => node.last_compile || Time.now,
        :edges => [],
        :resources => resources,
      },
    }
  end

  def resource_to_hash(resource)
    parameters = resource.param_values.inject({}) do |params,param_value|
      params.merge(param_value.param_name.name => param_value.value)
    end

    tags = resource.puppet_tags.map(&:name).uniq.sort

    {
      :type       => resource.restype,
      :title      => resource.title,
      :exported   => true,
      :parameters => parameters,
      :tags       => tags,
      :aliases    => [],
      :file       => resource.file,
      :line       => resource.line,
    }
  end
end
