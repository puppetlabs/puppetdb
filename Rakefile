require 'rake'

def run_beaker(test_files)
  config = ENV["BEAKER_CONFIG"] || "acceptance/config/vmpooler.cfg"
  options = ENV["BEAKER_OPTIONS"] || "acceptance/options/postgres.rb"
  preserve_hosts = ENV["BEAKER_PRESERVE_HOSTS"] || "never"
  no_provision = ENV["BEAKER_NO_PROVISION"] == "true" ? true : false
  color = ENV["BEAKER_COLOR"] == "false" ? false : true
  xml = ENV["BEAKER_XML"] == "true" ? true : false
  type = ENV["BEAKER_TYPE"] || "aio"
  keyfile = ENV["BEAKER_KEYFILE"] || nil
  collect_perf_data = ENV["BEAKER_COLLECT_PERF_DATA"] || nil

  beaker = "bundle exec beaker " +
     "-c '#{config}' " +
     "--type #{type} " +
     "--debug " +
     "--tests " + test_files + " " +
     "--options-file '#{options}' " +
     "--root-keys " +
     "--preserve-hosts #{preserve_hosts}"

  beaker += " --keyfile #{keyfile}" if keyfile
  beaker += " --no-color" unless color
  beaker += " --xml" if xml
  beaker += " --collect-perf-data #{collect_perf_data}" if collect_perf_data
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

begin
  require 'packaging'
  Pkg::Util::RakeUtils.load_packaging_tasks
rescue LoadError => e
  puts "Error loading packaging rake tasks: #{e}"
end
