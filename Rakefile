require 'rake'
require 'erb'
require 'facter'

JAR_FILE = 'puppetdb.jar'

RAKE_ROOT = File.dirname(__FILE__)

# Load tasks and variables for packaging automation
Dir[ File.join(RAKE_ROOT, 'ext', 'packaging', 'tasks', '*.rake') ].sort.each { |t| load t }

# We want to use puppetdb's package:tar and its dependencies, because it
# contains all the special java snowflake magicks, so we have to clear the
# packaging repo's. We also want to use puppetdb's clean task, since it has so
# much more clean than the packaging repo knows about
['package:tar', 'clean'].each do |task|
  Rake::Task[task].clear if Rake::Task.task_defined?(task)
end

# We establish variables used in the puppetdb tasks before hand
if (@build and @build.build_pe) || (ENV['PE_BUILD'] and ENV['PE_BUILD'].downcase == 'true')
  @pe = TRUE
  ENV['PATH'] = "/opt/puppet/bin:" + ENV['PATH']
else
  @pe = FALSE
end

if @pe
    @install_dir = "/opt/puppet/share/puppetdb"
    @etc_dir = "/etc/puppetlabs/puppetdb"
    @config_dir = "/etc/puppetlabs/puppetdb/conf.d"
    @initscriptname = "/etc/init.d/pe-puppetdb"
    @log_dir = "/var/log/pe-puppetdb"
    @lib_dir = "/opt/puppet/share/puppetdb"
    @name ="pe-puppetdb"
    @sbin_dir = "/opt/puppet/sbin"
    @pe_version = ENV['PE_VER'] || '2.9'
    @java_bin = "/opt/puppet/bin/java"
else
    @install_dir = "/usr/share/puppetdb"
    @etc_dir = "/etc/puppetdb"
    @config_dir = "/etc/puppetdb/conf.d"
    @initscriptname = "/etc/init.d/puppetdb"
    @log_dir = "/var/log/puppetdb"
    @lib_dir = "/var/lib/puppetdb"
    @link = "/usr/share/puppetdb"
    @name = "puppetdb"
    @sbin_dir = "/usr/sbin"
end

# We only need the ruby major, minor versions
@ruby_version = (ENV['RUBY_VER'] || Facter.value(:rubyversion))[0..2]
unless @ruby_version == '1.8' or @ruby_version == '1.9'
  STDERR.puts "RUBY_VER needs to be 1.8 or 1.9"
  exit 1
end

PATH = ENV['PATH']
DESTDIR=  ENV['DESTDIR'] || ''

@osfamily = (Facter.value(:osfamily) || "").downcase

case @osfamily
  when /debian/
    @plibdir = @pe ? '/opt/puppet/lib/ruby/1.8' : '/usr/lib/ruby/vendor_ruby'
  when /redhat/
    @plibdir = @pe ? '/opt/puppet/lib/ruby/site_ruby/1.8' : ( @ruby_version == '1.8' ? '/usr/lib/ruby/site_ruby/1.8' : '/usr/share/ruby/vendor_ruby' )
  when /suse/
    @plibdir = @pe ? '/opt/puppet/lib/ruby/site_ruby/1.8' : nil
end

@heap_dump_path = "#{@log_dir}/puppetdb-oom.hprof"
@default_java_args = "-Xmx192m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=#{@heap_dump_path} "

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
  desc "Build packages for testing"
  task :package do
    # TODO: I'm not proud of this.  The contents of the shell script(s) that
    # we call here need to be reconciled with Moses' standardized packaging
    # stuff, and a lot of the contents should probably be ported over to
    # Ruby instead of just shipping the nasty scripts.  However, this first step
    # at least gives us 1) VCS for this stuff, and 2) the ability to run
    # the two packaging builds in parallel.
    sh "sh ./ext/test/build_packages.sh"
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

