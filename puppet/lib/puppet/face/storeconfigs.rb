require 'puppet/face'
require 'tmpdir'

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
    description <<-DESC
      Generate a complete dump of all catalogs from the storeconfigs database,
      as a tarball which can be imported by PuppetDB. Returns the location of
      the output.
    DESC

    when_invoked do |options|
      require 'puppet/rails'

      workdir = Dir.mktmpdir

      begin
        Puppet::Rails.connect

        # Fetch all nodes, including exported resources and their params
        nodes = Puppet::Rails::Host.all(:include => {:resources => [:param_values, :puppet_tags]},
                                        :conditions => {:resources => {:exported => true}})

        catalogs = nodes.map {|node| node_to_catalog_hash(node)}

        catalog_dir = File.join(workdir, 'catalogs')
        FileUtils.mkdir(catalog_dir)

        catalogs.each do |catalog|
          filename = File.join(catalog_dir, "#{catalog[:data][:name]}.json")

          File.open(filename, 'w') do |file|
            file.puts catalog.to_pson
          end
        end

        node_names = nodes.map(&:name).sort

        timestamp = Time.now

        File.open(File.join(workdir, 'metadata.json'), 'w') do |file|
          metadata = {
            :timestamp => timestamp,
            :version => 2,
            :nodes => node_names,
          }

          file.puts metadata.to_pson
        end

        tarfile = destination_file(timestamp)

        if tar = Puppet::Util.which('tar')
          execute("cd #{workdir} && #{tar} -cf #{tarfile} *")

          FileUtils.rm_rf(workdir)

          if gzip = Puppet::Util.which('gzip')
            execute("#{gzip} #{tarfile}")
            "#{tarfile}.gz"
          else
            Puppet.warning "Can't find the `gzip` command to compress the tarball; output will not be compressed"
            tarfile
          end
        else
          Puppet.warning "Can't find the `tar` command to produce a tarball; output will remain in the temporary working directory"
          workdir
        end
      rescue => e
        # Clean up if something goes wrong. We don't want to ensure this,
        # because we want the directory to stick around in the case where they
        # don't have tar.
        FileUtils.rm_rf(workdir)
        raise
      end
    end

    when_rendering :console do |filename|
      "Exported storeconfigs data to #{filename}"
    end
  end

  # Returns the location to leave the output. This is really only here for testing. :/
  def destination_file(timestamp)
    File.expand_path("storeconfigs-#{timestamp.strftime('%Y%m%d%H%M%S')}.tar")
  end

  def execute(command)
    # Puppet::Util::Execution is the preferred way to do this in newer Puppets,
    # but isn't available in older versions. For the sake of not getting
    # deprecation warnings, we choose intelligently.
    if Puppet::Util::Execution.respond_to?(:execute)
      Puppet::Util::Execution.execute(command)
    else
      Puppet::Util.execute(command)
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
