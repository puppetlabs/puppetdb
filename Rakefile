require 'rake'
require 'erb'
require 'facter'

JAR_FILE = 'puppetdb.jar'

RAKE_ROOT = File.dirname(__FILE__)

# Load tasks and variables for packaging automation
begin
  load File.join(RAKE_ROOT, 'ext', 'packaging', 'packaging.rake')
rescue LoadError
end

# We establish variables used in the puppetdb tasks before hand
if defined?(Pkg) and defined?(Pkg::Config)
  if @pe = Pkg::Config.build_pe
    # If we're building PE, we need to set the project name to pe-puppetdb
    Pkg::Config.project = "pe-puppetdb"
  end
  @version = Pkg::Config.version
else
  @version = begin
    %x{lein with-profile ci pprint :version | tail -n 1 | cut -d\" -f2}.chomp
  rescue
    "0.0-dev-build"
  end
  if ENV['PE_BUILD'] and ENV['PE_BUILD'].downcase == 'true'
    @pe = TRUE
  end
end

# We only need the ruby major, minor versions
@ruby_version = (ENV['RUBY_VER'] || Facter.value(:rubyversion))[0..2]

# All variables have been set, so we can load the puppetdb tasks
Dir[ File.join(RAKE_ROOT, 'tasks','*.rake') ].sort.each { |t| load t }

task :version do
  puts @version
end

# An alias, for backwards compatibility
namespace :test do
  desc "DEPRECATED: use beaker:acceptance instead"
  task :beaker, [:test_files] => "beaker:acceptance"
end
