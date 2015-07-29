require 'rake'

RAKE_ROOT = File.dirname(__FILE__)

def run_beaker(test_files)
  config = ENV["BEAKER_CONFIG"] || "ec2-west-dev"
  options = ENV["BEAKER_OPTIONS"] || "postgres"
  preserve_hosts = ENV["BEAKER_PRESERVE_HOSTS"] || "never"
  no_provision = ENV["BEAKER_NO_PROVISION"] == "true" ? true : false
  color = ENV["BEAKER_COLOR"] == "false" ? false : true
  xml = ENV["BEAKER_XML"] == "true" ? true : false
  type = ENV["BEAKER_TYPE"] || "git"
  keyfile = ENV["BEAKER_KEYFILE"] || nil

  beaker = "bundle exec beaker " +
     "-c '#{RAKE_ROOT}/acceptance/config/#{config}.cfg' " +
     "--type #{type} " +
     "--debug " +
     "--tests " + test_files + " " +
     "--options-file 'acceptance/options/#{options}.rb' " +
     "--root-keys " +
     "--preserve-hosts #{preserve_hosts}"

  beaker += " --no-color" unless color
  beaker += " --xml" if xml
  beaker += " --no-provision" if no_provision

  sh beaker
end

namespace :beaker do
  desc "Run beaker based acceptance tests"
  task :acceptance, [:test_files] do |t, args|
    args.with_defaults(:test_files => 'acceptance/tests/')
    run_beaker(args[:test_files])
  end

  desc "Run beaker based performance tests"
  task :performance, :test_files do |t, args|
    args.with_defaults(:test_files => 'acceptance/performance/')
    run_beaker(args[:test_files])
  end

  desc "Run beaker based acceptance tests, leaving VMs in place for later runs"
  task :first_run, [:test_files] do |t, args|
    args.with_defaults(:test_files => 'acceptance/tests/')
    ENV["BEAKER_PRESERVE_HOSTS"] = "always"
    run_beaker(args[:test_files])
  end

  desc "Re-run beaker based acceptance tests, using previously provisioned VMs"
  task :rerun, [:test_files] do |t, args|
    args.with_defaults(:test_files => 'acceptance/tests/')
    ENV["PUPPETDB_SKIP_PRESUITE_PROVISIONING"] = "true"
    ENV["BEAKER_NO_PROVISION"] = "true"
    ENV["BEAKER_PRESERVE_HOSTS"] = "always"
    run_beaker(args[:test_files])
  end

  desc "List your VMs in ec2"
  task :list_vms do
    sh 'aws ec2 describe-instances  --filters "Name=key-name,Values=Beaker-${USER}*" --query "Reservations[*].Instances[*].[InstanceId, State.Name, PublicIpAddress]" --output table'
  end
end

build_defs_file = File.join(RAKE_ROOT, 'ext', 'build_defaults.yaml')
if File.exist?(build_defs_file)
  begin
    require 'yaml'
    @build_defaults ||= YAML.load_file(build_defs_file)
  rescue Exception => e
    STDERR.puts "Unable to load yaml from #{build_defs_file}:"
    raise e
  end
  @packaging_url  = @build_defaults['packaging_url']
  @packaging_repo = @build_defaults['packaging_repo']
  raise "Could not find packaging url in #{build_defs_file}" if @packaging_url.nil?
  raise "Could not find packaging repo in #{build_defs_file}" if @packaging_repo.nil?

  namespace :package do
    desc "Bootstrap packaging automation, e.g. clone into packaging repo"
    task :bootstrap do
      if File.exist?(File.join(RAKE_ROOT, "ext", @packaging_repo))
        puts "It looks like you already have ext/#{@packaging_repo}. If you don't like it, blow it away with package:implode."
      else
        cd File.join(RAKE_ROOT, 'ext') do
          %x{git clone #{@packaging_url}}
        end
      end
    end
    desc "Remove all cloned packaging automation"
    task :implode do
      rm_rf File.join(RAKE_ROOT, "ext", @packaging_repo)
    end
  end
end

begin
  load File.join(RAKE_ROOT, 'ext', 'packaging', 'packaging.rake')
rescue LoadError
end
