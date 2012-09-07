require 'rake'
require 'erb'
require 'facter'

if ENV['PE_BUILD'] and ENV['PE_BUILD'].downcase == 'true'
  @pe = TRUE
  ENV['PATH'] = "/opt/puppet/bin:" + ENV['PATH']
else
  @pe = FALSE
end

# We only need the ruby major, minor versions
@ruby_version = (ENV['RUBY_VER'] || Facter.value(:rubyversion))[0..2]
unless @ruby_version == '1.8' or @ruby_version == '1.9'
  STDERR.puts "RUBY_VER needs to be 1.8 or 1.9"
  exit 1
end

PATH = ENV['PATH']
DESTDIR=  ENV['DESTDIR'] || ''

def get_version
  if File.exists?('version')
    File.read('version').chomp
  elsif File.exists?('.git')
    %x{git describe}.chomp.gsub('-', '.').split('.')[0..3].join('.').gsub('v', '')
  else
    File.basename(pwd).split('-').last
  end
end

def get_debversion
  if @pe then
    return "#{(@version.include?("rc") ? @version.sub(/rc[0-9]+/, '-0.1\0') : @version + "-1")}puppet#{get_debrelease}"
  else
    return "#{(@version.include?("rc") ? @version.sub(/rc[0-9]+/, '-0.1\0') : @version + "-1")}puppetlabs#{get_debrelease}"
  end
end

def get_origversion
  @debversion.split('-')[0]
end

def get_rpmversion
  @version.match(/^([0-9.]+)/)[1]
end

def get_debrelease
  ENV['RELEASE'] || "1"
end

def get_rpmrelease
  ENV['RELEASE'] ||
    if @version.include?("rc")
      "0.1" + @version.gsub('-', '_').match(/rc[0-9]+.*/)[0]
    else
      "1"
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

def ln_sfT(src, dest)
  `ln -sfT "#{src}" "#{dest}"`
end

@osfamily = Facter.value(:osfamily).downcase

case @osfamily
  when /debian/
    @plibdir = @pe ? '/opt/puppet/lib/ruby/1.8' : ( @ruby_version == '1.8' ?  '/usr/lib/ruby/1.8' : '/usr/lib/ruby/vendor_ruby/1.9.1' )
  when /redhat/
    @plibdir = @pe ? '/opt/puppet/lib/ruby/site_ruby/1.8' : ( @ruby_version == '1.8' ? '/usr/lib/ruby/site_ruby/1.8' : '/usr/share/ruby/vendor_ruby' )
  when /suse/
    @plibdir = @pe ? '/opt/puppet/lib/ruby/site_ruby/1.8' : nil
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
    @cows = 'lenny', 'lucid', 'squeeze', 'precise'
    @pe_version = '2.5'
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


@heap_dump_path = "#{@log_dir}/puppetdb-oom.hprof"
@default_java_args = "-Xmx192m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=#{@heap_dump_path} "

@version      ||= get_version
@debversion   ||= get_debversion
@origversion  ||= get_origversion
@rpmversion   ||= get_rpmversion
@rpmrelease   ||= get_rpmrelease


desc "Create a source install of PuppetDB"
task :sourceinstall do
  ENV['SOURCEINSTALL'] = 1
  Rake::Task[:install].invoke
end

def erb(erbfile,  outfile)
  if ENV['SOURCEINSTALL'] == 1
    @install_dir = "#{DESTDIR}/@install_dir"
    @config_dir = "#{DESTDIR}/@config_dir"
    @initscriptname = "#{DESTDIR}/@initscript"
    @log_dir = "#{DESTDIR}/@log_dir"
    @lib_dir = "#{DESTDIR}/@lib_dir"
    @link = "#{DESTDIR}/@link"
  end
  template = File.read(erbfile)
  message = ERB.new(template, nil, "-")
  output = message.result(binding)
  File.open(outfile, 'w') { |f| f.write output }
  puts "Generated: #{outfile}"
end

JAR_FILE_V = "puppetdb-#{@version}-standalone.jar"
JAR_FILE = "puppetdb.jar"

task :default => [ :package ]

desc "Build the uberjar"
task :uberjar => [  ] do
  if `which lein`
    sh "lein uberjar"
    mv JAR_FILE_V, JAR_FILE
  else
    puts "You need lein on your system"
    exit 1
  end
end

desc "Create a source tar archive"
task :package => [ :clobber, JAR_FILE, :template  ] do
  temp = `mktemp -d -t tmpXXXXXX`.strip
  workdir = File.join(temp, "puppetdb-#{@version}")
  mkdir_p workdir
  FileList[ "ext", "*.md", JAR_FILE, "spec", "Rakefile" ].each do |f|
    cp_pr f, workdir
  end
  # Lay down version file for later reading
  File.open(File.join(workdir,'version'), File::CREAT|File::TRUNC|File::RDWR, 0644) do |f|
    f.puts @version
  end
  mv "#{workdir}/ext/files/debian", workdir
  cp_pr "puppet", "#{workdir}/ext/master"
  mkdir_p "pkg"
  pkg_dir = File.expand_path(File.join(".", "pkg"))
  sh "cd #{temp}; tar --exclude=.gitignore -zcf #{pkg_dir}/puppetdb-#{@version}.tar.gz puppetdb-#{@version}"
  rm_rf temp
  puts
  puts "Wrote #{`pwd`.strip}/pkg/puppetdb-#{@version}"
end

file JAR_FILE do |t|
  Rake::Task[:uberjar].invoke
end

task :allclean => [ :clobber ]

desc "Remove build artifacts (other than clojure (lein) builds)"
task :clean do
  rm_rf FileList["ext/files", "pkg", "*.tar.gz"]
end

desc "Get rid of build artifacts including clojure (lein) builds"
task :clobber => [ :clean ] do
  rm_rf FileList["puppetdb*jar"]
end

task :version do
  puts @version
end

file "ext/files/config.ini" => [ :template, JAR_FILE ]   do
end

task :template => [ :clean ] do
   mkdir_p "ext/files/debian"

   # files for deb and rpm
   erb "ext/templates/log4j.properties.erb", "ext/files/log4j.properties"
   erb "ext/templates/config.ini.erb" , "ext/files/config.ini"
   erb "ext/templates/jetty.ini.erb",  "ext/files/jetty.ini"
   erb "ext/templates/repl.ini.erb",  "ext/files/repl.ini"
   erb "ext/templates/database.ini.erb",  "ext/files/database.ini"
   erb "ext/templates/puppetdb-foreground.erb",  "ext/files/puppetdb-foreground"
   chmod 0700, "ext/files/puppetdb-foreground"

   # files for deb
   erb "ext/templates/init_debian.erb", "ext/files/debian/#{@name}.init"
   erb "ext/templates/puppetdb_default.erb", "ext/files/debian/#{@name}.default"
   erb "ext/templates/deb/control.erb", "ext/files/debian/control"
   erb "ext/templates/deb/prerm.erb", "ext/files/debian/#{@name}.prerm"
   erb "ext/templates/deb/postrm.erb", "ext/files/debian/#{@name}.postrm"
   erb "ext/templates/deb/base.install.erb", "ext/files/debian/#{@name}.install"
   erb "ext/templates/deb/terminus.install.erb", "ext/files/debian/#{@name}-terminus.install"
   erb "ext/templates/deb/rules.erb", "ext/files/debian/rules"
   chmod 0755, "ext/files/debian/rules"
   erb "ext/templates/deb/changelog.erb", "ext/files/debian/changelog"
   erb "ext/templates/deb/terminus.postinst.erb", "ext/files/debian/#{@name}-terminus.postinst"
   erb "ext/templates/deb/preinst.erb", "ext/files/debian/#{@name}.preinst"
   erb "ext/templates/deb/postinst.erb", "ext/files/debian/#{@name}.postinst"
   erb "ext/templates/logrotate.erb", "ext/files/debian/#{@name}.logrotate"
   erb "ext/templates/init_debian.erb", "ext/files/#{@name}.debian.init"
   cp_pr FileList["ext/templates/deb/*"], "ext/files/debian"
   cp_pr "ext/templates/puppetdb-ssl-setup", "ext/files"
   chmod 0700, "ext/files/puppetdb-ssl-setup"
   rm_rf FileList["ext/files/debian/*.erb"]

   # files for rpm
   erb "ext/templates/logrotate.erb", "ext/files/puppetdb.logrotate"
   erb "ext/templates/init_redhat.erb", "ext/files/puppetdb.redhat.init"
   erb "ext/templates/puppetdb_default.erb", "ext/files/puppetdb.default"
   erb "ext/templates/puppetdb.spec.erb", "ext/files/#{@name}.spec"

   # developer utility files for redhat
   mkdir_p "ext/files/dev/redhat"
   erb "ext/templates/dev/redhat/redhat_dev_preinst.erb", "ext/files/dev/redhat/redhat_dev_preinst"
   erb "ext/templates/dev/redhat/redhat_dev_postinst.erb", "ext/files/dev/redhat/redhat_dev_postinst"


end

desc "Install PuppetDB (DESTDIR and PE_BUILD optional arguments)"
task :install => [  JAR_FILE  ] do
  unless File.exists?("ext/files/config.ini")
    Rake::Task[:template].invoke
  end

  require 'facter'
  raise "Oh damn. You need a newer facter or better facts. Facter version: #{Facter.version}" if Facter.value(:osfamily).nil?
  @osfamily = Facter.value(:osfamily).downcase
  mkdir_p "#{DESTDIR}/#{@install_dir}"
  mkdir_p "#{DESTDIR}/#{@config_dir}"
  mkdir_p "#{DESTDIR}/#{@config_dir}/.."
  mkdir_p "#{DESTDIR}/#{@log_dir}"
  mkdir_p "#{DESTDIR}/etc/init.d/"
  mkdir_p "#{DESTDIR}/#{@lib_dir}"
  mkdir_p "#{DESTDIR}/#{@sbin_dir}"
  mkdir_p "#{DESTDIR}/etc/logrotate.d/"
  ln_sfT @config_dir, "#{DESTDIR}/#{@lib_dir}/config"
  ln_sfT @log_dir, "#{DESTDIR}/#{@install_dir}/log"

  unless @pe
    mkdir_p "#{DESTDIR}/var/lib/puppetdb/state"
    mkdir_p "#{DESTDIR}/var/lib/puppetdb/db"
    mkdir_p "#{DESTDIR}/var/lib/puppetdb/mq"
    ln_sfT "#{@lib_dir}/state", "#{DESTDIR}#{@link}/state"
    ln_sfT "#{@lib_dir}/db", "#{DESTDIR}#{@link}/db"
    ln_sfT "#{@lib_dir}/mq", "#{DESTDIR}#{@link}/mq"
    mkdir_p "#{DESTDIR}#/etc/puppetdb"
  else
    mkdir_p "#{DESTDIR}#{@lib_dir}/state"
    mkdir_p "#{DESTDIR}#{@lib_dir}/db"
    mkdir_p "#{DESTDIR}#{@lib_dir}/mq"
    mkdir_p "#{DESTDIR}#/etc/puppetlabs/puppetdb"
  end

  cp_p JAR_FILE, "#{DESTDIR}/#{@install_dir}"
  cp_pr "ext/files/config.ini", "#{DESTDIR}/#{@config_dir}"
  cp_pr "ext/files/database.ini", "#{DESTDIR}/#{@config_dir}"
  cp_pr "ext/files/jetty.ini", "#{DESTDIR}/#{@config_dir}"
  cp_pr "ext/files/repl.ini", "#{DESTDIR}/#{@config_dir}"
  cp_pr "ext/files/puppetdb.logrotate", "#{DESTDIR}/etc/logrotate.d/#{@name}"
  cp_pr "ext/files/log4j.properties", "#{DESTDIR}/#{@config_dir}/.."
  cp_pr "ext/files/puppetdb-ssl-setup", "#{DESTDIR}/#{@sbin_dir}"
  cp_pr "ext/files/puppetdb-foreground", "#{DESTDIR}/#{@sbin_dir}"

  # figure out which init script to install based on facter
  if @osfamily == "redhat" || @osfamily == "suse"
    mkdir_p "#{DESTDIR}/etc/sysconfig"
    mkdir_p "#{DESTDIR}/etc/rc.d/init.d/"
    cp_p "ext/files/puppetdb.default", "#{DESTDIR}/etc/sysconfig/#{@name}"
    cp_p "ext/files/puppetdb.redhat.init", "#{DESTDIR}/etc/rc.d/init.d/#{@name}"
    chmod 0755, "#{DESTDIR}/etc/rc.d/init.d/#{@name}"
  elsif @osfamily == "debian"
    mkdir_p "#{DESTDIR}/etc/default"
    cp_p "ext/files/puppetdb.default", "#{DESTDIR}/etc/default/#{@name}"
    cp_pr "ext/files/#{@name}.debian.init", "#{DESTDIR}/etc/init.d/#{@name}"
    chmod 0755, "#{DESTDIR}/etc/init.d/#{@name}"
  else
    raise "Unknown or unsupported osfamily: #{@osfamily}"
  end
  chmod 0750, "#{DESTDIR}/#{@config_dir}"
  chmod 0640, "#{DESTDIR}/#{@config_dir}/../log4j.properties"
  chmod 0700, "#{DESTDIR}/#{@sbin_dir}/puppetdb-ssl-setup"
  chmod 0700, "#{DESTDIR}/#{@sbin_dir}/puppetdb-foreground"
end

desc "INTERNAL USE ONLY: Install the terminus components from the puppetdb package tarball onto an existing puppet setup"
task :terminus do
  mkdir_p "#{DESTDIR}#{@plibdir}/puppet/indirector"
  cp_pr FileList["ext/master/lib/puppet/*"], "#{DESTDIR}#{@plibdir}/puppet/"
  #TODO Fix up specs when the specs ship with the puppet packages
end

desc "Install the terminus components from puppetdb source tree onto an existing puppet setup"
task :sourceterminus do
  mkdir_p "#{DESTDIR}#{@plibdir}/puppet/indirector"
  cp_pr FileList["puppet/lib/puppet/*"], "#{DESTDIR}#{@plibdir}/puppet/"
end

desc "Build a Source RPM for puppetdb"
task :srpm => [ :package ] do
  %x{which rpmbuild}
  if $? != 0
    puts "rpmbuild command not found...exiting"
    exit 1
  end
  temp = `mktemp -d -t tmpXXXXXX`.strip
  RPM_DEFINE="--define \"%dist .el5\" --define \"%_topdir  #{temp}\" "
  RPM_OLD_VERSION='--define "_source_filedigest_algorithm 1" --define "_binary_filedigest_algorithm 1" \
     --define "_binary_payload w9.gzdio" --define "_source_payload w9.gzdio" \
     --define "_default_patch_fuzz 2"'
  args = RPM_DEFINE + ' ' + RPM_OLD_VERSION
  mkdir_p temp
  mkdir_p 'pkg/rpm'
  mkdir_p "#{temp}/SOURCES"
  mkdir_p "#{temp}/SPECS"
  mkdir_p "#{temp}/SRPMS"
  cp_p "pkg/puppetdb-#{@version}.tar.gz", "#{temp}/SOURCES"
  cp_p "ext/files/#{@name}.spec", "#{temp}/SPECS"
  sh "rpmbuild #{args} -bs --nodeps #{temp}/SPECS/#{@name}.spec"
  output = `ls #{temp}/SRPMS/*rpm`
  mv FileList["#{temp}/SRPMS/*rpm"], "pkg/rpm"
  rm_rf temp
  puts
  puts "Wrote #{`pwd`.strip}/pkg/rpm/#{output.split('/')[-1]}"
end

desc "Build deb package"
task :deb  => [ :package ] do
  temp = `mktemp -d -t tmpXXXXXX`.strip
  mkdir_p temp
  cp_p "pkg/puppetdb-#{@version}.tar.gz", "#{temp}"
  sh "cd #{temp}; tar  -z -x -f #{temp}/puppetdb-#{@version}.tar.gz"
  mv "#{temp}/puppetdb-#{@version}", "#{temp}/puppetdb-#{@debversion}"
  mv "#{temp}/puppetdb-#{@version}.tar.gz", "#{temp}/#{@name}_#{@origversion}.orig.tar.gz"
  cd "#{temp}/puppetdb-#{@debversion}" do
    if @pe
      @cows.each do |cow|
        mkdir "#{temp}/#{cow}"
        ENV['DIST'] = cow
        ENV['ARCH'] = 'i386'
        ENV['PE_VER'] ||= @pe_version
        sh "pdebuild --buildresult #{temp}/#{cow} \
        --pbuilder cowbuilder -- \
        --override-config \
        --othermirror=\"deb http://freight.puppetlabs.lan #{ENV['PE_VER']} #{cow}\" \
        --basepath /var/cache/pbuilder/base-#{cow}-i386.cow/"
      end
    else
      sh 'debuild --no-lintian  -uc -us'
    end
  end
  mkdir_p 'pkg/deb'
  rm_rf "#{temp}/puppetdb-#{@debversion}"
  mv FileList["#{temp}/*"], "pkg/deb"
  rm_rf temp
  puts
  puts "Wrote debian package output to #{`pwd`.strip}/pkg/deb"
end

namespace :package do
  desc "Create source tarball"
  task :tar => [ :package ]

  desc "Create source rpm for rebuild in a tool such as mock"
  task :srpm => [ :srpm ]

  desc "Create debian package"
  task :deb => [ :deb ]
end
