require 'puppet/util/puppetdb'
require 'puppet/face'

if Puppet::Util::Puppetdb.puppet3compat?
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
      Generate a dump of all catalogs from the storeconfigs database, as a
      tarball which can be imported by PuppetDB. Only exported resources are
      included; non-exported resources, edges, facts, or other data are
      omitted. Returns the location of the output.
    DESC

      when_invoked do |options|

        require 'puppet/rails'

        tmpdir = Dir.mktmpdir
        workdir = File.join(tmpdir, 'puppetdb-bak')
        Dir.mkdir(workdir)

        begin
          Puppet::Rails.connect

          timestamp = Time.now

          # Fetch all nodes, including exported resources and their params
          nodes = Puppet::Rails::Host.all(:include => {:resources => [:param_values, :puppet_tags]},
                                          :conditions => {:resources => {:exported => true}})

          catalogs = nodes.map { |node| node_to_catalog_hash(node, timestamp.iso8601(5)) }

          catalog_dir = File.join(workdir, 'catalogs')
          FileUtils.mkdir(catalog_dir)

          catalogs.each do |catalog|
            filename = File.join(catalog_dir, "#{catalog[:certname]}.json")

            File.open(filename, 'w') do |file|
              file.puts catalog.to_json
            end
          end

          node_names = nodes.map(&:name).sort

          File.open(File.join(workdir, 'export-metadata.json'), 'w') do |file|
            metadata = {
              'timestamp' => timestamp,
              'command_versions' => {
                'replace_catalog' => 6,
              }
            }

            file.puts metadata.to_json
          end

          tarfile = destination_file(timestamp)

          if tar = Puppet::Util.which('tar')
            execute("cd #{tmpdir} && #{tar} -cf #{tarfile} puppetdb-bak")

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

    # Execute a command using Puppet's execution static method.
    #
    # @param command [Array<String>, String] the command to execute. If it is
    #   an Array the first element should be the executable and the rest of the
    #   elements should be the individual arguments to that executable.
    # @return [Puppet::Util::Execution::ProcessOutput] output as specified by options
    # @raise [Puppet::ExecutionFailure] if the executed chiled process did not exit with status == 0 and `failonfail` is
    #   `true`.
    def execute(command)
      Puppet::Util::Execution.execute(command)
    end

    def node_to_catalog_hash(node, timestamp)
      resources = node.resources.map { |resource| resource_to_hash(resource) }
      edges = node.resources.map { |resource| resource_to_edge_hash(resource) }

      {
        :environment => "production",
        :metadata => {
          :api_version => 1,
        },
        :certname => node.name,
        :version => node.last_compile || Time.now,
        :edges => edges,
        :resources => resources + [stage_main_hash],
        :timestamp => timestamp,
        :producer_timestamp => timestamp,
      }
    end

    def resource_to_hash(resource)
      parameters = resource.param_values.inject({}) do |params,param_value|
        if params.has_key?(param_value.param_name.name)
          value = [params[param_value.param_name.name],param_value.value].flatten
        else
          value = param_value.value
        end
        params.merge(param_value.param_name.name => value)
      end

      tags = resource.puppet_tags.map(&:name).uniq.sort

      hash = {
        :type       => resource.restype,
        :title      => resource.title,
        :exported   => true,
        :parameters => parameters,
        :tags       => tags,
      }

      hash[:file] = resource.file if resource.file
      hash[:line] = resource.line if resource.line

      hash
    end

    # The catalog *must* have edges, so everything is contained by Stage[main]!
    def resource_to_edge_hash(resource)
      {
        'source' => {'type' => 'Stage', 'title' => 'main'},
        'target' => {'type' => resource.restype, 'title' => resource.title},
        'relationship' => 'contains',
      }
    end

    def stage_main_hash
      {
        :type       => 'Stage',
        :title      => 'main',
        :exported   => false,
        :parameters => {},
        :tags       => ['stage', 'main'],
      }
    end
  end
else
  Puppet::Face.define(:storeconfigs, '0.0.1') do
    copyright "Puppet Labs", 2011
    license   "Apache 2 license"

    summary "storeconfigs is not supported on Puppet 4.0.0+"
    description <<-DESC
    Users needing this feature should migrate using Puppet 3.7.2 or a more recent
    3.7 release.
  DESC
  end
end
