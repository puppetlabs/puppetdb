# Task to clone the packaging repo into 'ext/packaging'

build_defs_file = File.join(RAKE_ROOT, 'ext', 'build_defaults.yaml')
if File.exist?(build_defs_file)
  begin
    require 'yaml'
    @build_defaults ||= YAML.load_file(build_defs_file)
  rescue Exception => e
    STDERR.puts "Unable to load yaml from #{build_defs_file}:"
    STDERR.puts e
  end
   @packaging_url  = @build_defaults['packaging_url']
   @packaging_repo = @build_defaults['packaging_repo']
   raise "Could not find packaging url in #{build_defs_file}" if @packaging_url.nil?
   raise "Could not find packaging repo in #{build_defs_file}" if @packaging_repo.nil?

  namespace :package do
    desc "Bootstrap packaging automation, e.g. clone into packaging repo"
    task :bootstrap do
      if File.exist?(File.join(RAKE_ROOT, 'ext', @packaging_repo))
        puts "It looks like you already have ext/#{@packaging_repo}. If you don't like it, blow it away with packa    ge:implode."
      else
        cd 'ext' do
          %x{git clone #{@packaging_url}}
        end
      end
    end
    desc "Remove all cloned packaging automation"
    task :implode do
      rm_rf File.join(RAKE_ROOT, 'ext', @packaging_repo)
    end
  end
end

