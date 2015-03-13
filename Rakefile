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

def ln_sf(src, dest)
  if !File.exist?(dest)
    sh "ln -sf #{src} #{dest}"
  end
end

def cp_pr(src, dest, options={})
  mandatory = {:preserve => true}
  cp_r(src, dest, options.merge(mandatory))
end

def cp_p(src, dest, options={})
  mandatory = {:preserve => true}
  cp(src, dest, options.merge(mandatory))
end

# We want to use puppetdb's package:tar and its dependencies, because it
# contains all the special java snowflake magicks, so we have to clear the
# packaging repo's. We also want to use puppetdb's clean task, since it has so
# much more clean than the packaging repo knows about
['package:tar', 'clean'].each do |task|
  Rake::Task[task].clear if Rake::Task.task_defined?(task)
end

# We establish variables used in the puppetdb tasks before hand
if defined?(Pkg) and defined?(Pkg::Config)
  if @pe = Pkg::Config.build_pe
    # If we're building PE, we need to set the project name to pe-puppetdb
    Pkg::Config.project = "pe-puppetdb"
  end
  @version = Pkg::Config.version
else
  begin
    %x{which git >/dev/null 2>&1}
    if $?.success?
      @version = %x{git describe --always --dirty}
      if $?.success?
        @version.chomp!
      end
    end
  rescue
    @version = "0.0-dev-build"
  end
  if ENV['PE_BUILD'] and ENV['PE_BUILD'].downcase == 'true'
    @pe = TRUE
  end
end

ENV['PATH'] = "/opt/puppet/bin:" + ENV['PATH'] if @pe

@osfamily = (Facter.value(:osfamily) || "").downcase

# Specific minimum pinning for Puppet & Facter versions
@puppetminversion = "3.5.1"
@facterminversion = "1.7.0"

if @pe
    @install_dir = "/opt/puppet/share/puppetdb"
    @etc_dir = "/etc/puppetlabs/puppetdb"
    @config_dir = "/etc/puppetlabs/puppetdb/conf.d"
    @lib_dir = "/opt/puppet/share/puppetdb"
    @libexec_dir = "/opt/puppet/libexec/puppetdb"
    @name ="pe-puppetdb"
    @sbin_dir = "/opt/puppet/sbin"
    @pe_version = ENV['PE_VER'] || '3.0'
    @java_bin = "/opt/puppet/bin/java"
else
    @install_dir = case @osfamily
      when /openbsd/
        "/usr/local/share/puppetdb"
      else
        "/usr/share/puppetdb"
      end
    @etc_dir = "/etc/puppetdb"
    @config_dir = "/etc/puppetdb/conf.d"
    @lib_dir = "/var/lib/puppetdb"
    @libexec_dir = case @osfamily
      when /openbsd/
        "/usr/local/libexec/puppetdb"
      when /redhat/, /suse/, /darwin/, /bsd/
        "/usr/libexec/puppetdb"
      else
        "/usr/lib/puppetdb"
      end
    @link = "/usr/share/puppetdb"
    @name = "puppetdb"
    @sbin_dir = case @osfamily
      when /archlinux/
        "/usr/bin"
      else
        "/usr/sbin"
      end
end

@initscriptname = "/etc/init.d/#{@name}"
@log_dir = "/var/log/#{@name}"

# We only need the ruby major, minor versions
@ruby_version = (ENV['RUBY_VER'] || Facter.value(:rubyversion))[0..2]
unless ['1.8','1.9'].include?(@ruby_version)
  STDERR.puts "Warning: Existing rake commands are untested on #{@ruby_version} currently supported rubies include 1.8 or 1.9"
end

PATH = ENV['PATH']
DESTDIR=  ENV['DESTDIR'] || ''
PE_SITELIBDIR = "/opt/puppet/lib/ruby/site_ruby/1.9.1"


case @osfamily
  when /debian/
    @plibdir = @pe ? PE_SITELIBDIR : '/usr/lib/ruby/vendor_ruby'
  when /redhat/
    @plibdir = @pe ? PE_SITELIBDIR : ( @ruby_version == '1.8' ? %x(ruby -rrbconfig -e 'puts RbConfig::CONFIG["sitelibdir"]').chomp : %x(ruby -rrbconfig -e 'puts RbConfig::CONFIG["vendorlibdir"]').chomp )
  when /suse/
    @plibdir = @pe ? PE_SITELIBDIR : (%x(ruby -rrbconfig -e "puts RbConfig::CONFIG['sitelibdir']").chomp)
  when /openbsd/
    @plibdir = @pe ? PE_SITELIBDIR : '/usr/local/lib/ruby/site_ruby/1.9.1'
  when /archlinux/
    @plibdir = @pe ? PE_SITELIBDIR : (%x(ruby -rrbconfig -e 'puts RbConfig::CONFIG["vendorlibdir"]').chomp)
end

# The Puppet 4 load path
@labsdir = '/opt/puppetlabs'
@labsbindir = "#{@labsdir}/bin"
@p4libdir = "#{@labsdir}/puppet/lib/ruby/vendor_ruby"

@heap_dump_path = "#{@log_dir}/puppetdb-oom.hprof"
@default_java_args = "-Xmx192m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=#{@heap_dump_path} -Djava.security.egd=file:/dev/urandom"

# All variables have been set, so we can load the puppetdb tasks
Dir[ File.join(RAKE_ROOT, 'tasks','*.rake') ].sort.each { |t| load t }

task :default => [ :package ]

task :allclean => [ :clobber ]

desc "Remove build artifacts (other than clojure (lein) builds)"
task :clean do
  rm_rf FileList["ext/files", "pkg", "*.tar.gz"]
end

desc "Get rid of build artifacts including clojure (lein) builds"
task :clobber => [ :clean ] do
  rm_rf FileList["target/puppetdb*jar"]
end

task :version do
  puts @version
end

file "ext/files/config.ini" => [ :template, JAR_FILE ]   do
end

namespace :test do
  desc "Run beaker based acceptance tests"
  task :beaker, :test_files do |t, args|
    args.with_defaults(:test_files => 'acceptance/tests/')
    config = ENV["BEAKER_CONFIG"] || "vbox-el6-64mda"
    options = ENV["BEAKER_OPTIONS"] || "postgres"
    preserve_hosts = ENV["BEAKER_PRESERVE_HOSTS"] || "never"
    color = ENV["BEAKER_COLOR"] == "false" ? false : true
    xml = ENV["BEAKER_XML"] == "true" ? true : false
    type = ENV["BEAKER_TYPE"] || "git"

    beaker = "beaker " +
       "-c '#{RAKE_ROOT}/acceptance/config/#{config}.cfg' " +
       "--type #{type} " +
       "--debug " +
       "--tests " + args[:test_files] + " " +
       "--options-file 'acceptance/options/#{options}.rb' " +
       "--root-keys " +
       "--preserve-hosts #{preserve_hosts}"

    beaker += " --no-color" unless color
    beaker += " --xml" if xml

    sh beaker
  end
end

# The first package build tasks in puppetdb were rake deb and rake srpm (due to
# a cyclical dependency bug, the namespaced aliases to these tasks never worked
# actually worked). The packaging repo doesn't provide rake deb/srpm (they're
# namespaced as package:deb/srpm) and its just as well so we don't conflict
# here when we try to emulate the original behavior here backwards
# compatibility.  These two tasks will force a reload of the packaging repo and
# then use it to do the same thing the original tasks did.

desc 'Build deb package'
task :deb => [ 'package:implode', 'package:bootstrap', 'package:deb' ]

desc 'Build a Source rpm for puppetdb'
task :srpm => [ 'package:implode', 'package:bootstrap', 'package:srpm' ]

