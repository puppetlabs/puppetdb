
require 'rake'
require 'erb'

PE_BUILD= ENV['PE_BUILD'] || ''
DESTDIR=  ENV['DESTDIR'] || ''

def version
# This ugly bit removes the gSHA1 portion of the describe as that causes failing tests
  if File.exists?('.git')
    %x{git describe}.gsub('-', '.').split('.')[0..3].join('.').to_s.gsub('v', '')
  else
    %x{pwd}.strip!.split('.')[-1]
  end
end

if PE_BUILD == "true" or PE_BUILD == "TRUE"
    @install_dir = "/opt/puppet/share/puppetdb"
    @config_dir = "/etc/puppetlabs/puppetdb"
    @initscriptname = "/etc/init.d/pe-puppetdb"
    @log_dir = "/var/log/pe-puppetdb"
    @lib_dir = "/opt/puppet/share/puppetdb"
    @name ="pe-puppetdb"
    @pe = true
    @version = version
else
    @install_dir = "/usr/share/puppetdb"
    @config_dir = "/etc/puppetdb"
    @initscriptname = "/etc/init.d/puppetdb"
    @log_dir = "/var/log/puppetdb"
    @lib_dir = "/usr/share/puppetdb"
    @link = "/var/lib/puppetdb"
    @name = "puppetdb"
    @pe = false
    @version = version
end

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
    sh "mv #{JAR_FILE_V} #{JAR_FILE}"
  else
    puts "You need lein on your system"
    exit 1
  end
end

desc "Create a source tar archive"
task :package => [ JAR_FILE, :template  ] do
  workdir = "pkg/puppetdb-#{@version}"
  mkdir_p workdir
  filelist = [ "ext", "*.md", JAR_FILE, "spec", "Rakefile" ]
  filelist.each do |f|
    sh "cp -pr #{f} #{workdir}"
  end
  sh "mv #{workdir}/ext/files/debian #{workdir}"
  sh "cd pkg; tar --exclude=.gitignore -zcf puppetdb-#{@version}.tar.gz puppetdb-#{@version}"
  sh "rm -rf #{workdir}"
  puts
  puts "Wrote #{`pwd`.strip}/pkg/puppetdb-#{@version}"


end

file JAR_FILE do |t|
  Rake::Task[:uberjar].invoke
end

task :allclean => [ :clobber ]

desc "Remove build artifacts (other than clojure (lein) builds)"
task :clean do
  sh  "rm -rf ext/files"
  sh  "rm -f *.tar.gz"
  sh  "rm -rf pkg"
end

desc "Get rid of build artifacts including clojure (lein) builds"
task :clobber => [ :clean ] do
  sh  "rm -f puppetdb*.jar"
end


file "ext/files/config.ini" => [ :template, JAR_FILE ]   do
end

task :template => [ ] do
   mkdir_p "ext/files/debian"
   # files for deb and rpm
   erb "ext/templates/log4j.properties.erb", "ext/files/log4j.properties"
   erb "ext/templates/config.ini.erb" , "ext/files/config.ini"

   # files for deb
   erb "ext/templates/init_debian.erb", "ext/files/debian/#{@name}.init"
   erb "ext/templates/puppetdb_default.erb", "ext/files/debian/#{@name}.default"
   erb "ext/templates/deb/control.erb", "ext/files/debian/control"
   erb "ext/templates/deb/postrm.erb", "ext/files/debian/#{@name}.postrm"
   erb "ext/templates/deb/base.install.erb", "ext/files/debian/#{@name}.install"
   erb "ext/templates/deb/terminus.install.erb", "ext/files/debian/#{@name}-terminus.install"
   erb "ext/templates/deb/rules.erb", "ext/files/debian/rules"
   sh "chmod 755 ext/files/debian/rules"
   erb "ext/templates/deb/changelog.erb", "ext/files/debian/changelog"
   erb "ext/templates/deb/postinst.erb", "ext/files/debian/#{@name}.postinst"
   erb "ext/templates/logrotate.erb", "ext/files/debian/#{@name}.logrotate"
   erb "ext/templates/init_debian.erb", "ext/files/puppetdb.debian.init"
   sh "cp -pr ext/templates/deb/* ext/files/debian"
   sh "rm -f ext/files/debian/*.erb"

   # files for rpm
   erb "ext/templates/logrotate.erb", "ext/files/puppetdb.logrotate"
   erb "ext/templates/init_redhat.erb", "ext/files/puppetdb.redhat.init"
   erb "ext/templates/puppetdb_default.erb", "ext/files/puppetdb.default"
   erb "ext/templates/puppetdb.spec.erb", "ext/files/#{@name}.spec"

end

desc "Install PuppetDB (DESTDIR and PE_BUILD optional arguments)"
task :install => [  JAR_FILE  ] do
  unless File.exists?("ext/files/config.ini")
    Rake::Task[:template].invoke
  end

  require 'facter'
  osfamily = Facter.value(:osfamily).downcase
  mkdir_p "#{DESTDIR}/#{@install_dir}"
  mkdir_p "#{DESTDIR}/#{@config_dir}"
  mkdir_p "#{DESTDIR}/#{@log_dir}"
  mkdir_p "#{DESTDIR}/etc/init.d/"
  mkdir_p "#{DESTDIR}/#{@lib_dir}"
  mkdir_p "#{DESTDIR}/etc/logrotate.d/"
  sh "ln -sf #{@config_dir} #{DESTDIR}/#{@lib_dir}/config"
  sh "ln -sf #{@log_dir} #{DESTDIR}/#{@install_dir}/log"

  if PE_BUILD == false or PE_BUILD == nil or PE_BUILD == ''
    mkdir_p "#{DESTDIR}/var/lib/puppetdb/state"
    mkdir_p "#{DESTDIR}/var/lib/puppetdb/db"
    sh "ln -sf #{@link}/state #{DESTDIR}#{@lib_dir}/state"
    sh "ln -sf #{@link}/db #{DESTDIR}#{@lib_dir}/db"
  else
    mkdir_p "#{DESTDIR}#{@lib_dir}/state"
    mkdir_p "#{DESTDIR}#{@lib_dir}/db"
  end

  sh "cp -p puppetdb.jar #{DESTDIR}/#{@install_dir}"
  sh "cp -pr ext/files/log4j.properties #{DESTDIR}/#{@config_dir}/log4j.properties"
  sh "cp -pr ext/files/config.ini       #{DESTDIR}/#{@config_dir}/config.ini"
  sh "cp -pr ext/files/puppetdb.logrotate  #{DESTDIR}/etc/logrotate.d/#{@name}"

  # figure out which init script to install based on facter
  if osfamily.downcase == "RedHat".downcase
    mkdir_p "#{DESTDIR}/etc/sysconfig"
    mkdir_p "#{DESTDIR}/etc/rc.d/init.d/"
    sh "cp -p ext/files/puppetdb.default #{DESTDIR}/etc/sysconfig/#{@name}"
    sh "cp -p ext/files/puppetdb.redhat.init  #{DESTDIR}/etc/rc.d/init.d/#{@name}"
    sh "chmod 755 #{DESTDIR}/etc/rc.d/init.d/#{@name}"
  else
    mkdir_p "#{DESTDIR}/etc/default"
    sh "cp -p ext/files/puppetdb.default #{DESTDIR}/etc/default/#{@name}"
    sh "cp -pr ext/files/puppetdb.debian.init #{DESTDIR}/etc/init.d/#{@name}"
    sh "chmod 755 #{DESTDIR}/etc/init.d/#{@name}"
  end
end

desc "Install the terminus components onto an existing puppet setup"
task :terminus do
  require 'facter'
  osfamily = Facter.value(:osfamily).downcase
  if osfamily.downcase =~ /debian/    and PE_BUILD == ''
    @plibdir = '/usr/lib/ruby/1.8'
  elsif osfamily.downcase =~ /debian/ and PE_BUILD.downcase == "true"
    @plibdir = '/opt/puppet/lib/ruby/1.8'
  elsif osfamily.downcase =~ /redhat/ and PE_BUILD == ''
    @plibdir = '/usr/lib/ruby/site_ruby/1.8'
  elsif osfamily.downcase =~ /redhat/ and PE_BUILD.downcase == "true"
    @plibdir = '/opt/puppet/lib/ruby/site_ruby/1.8'
  end

puts "PLIBDIR #{@plibdir}"
  mkdir_p "#{DESTDIR}#{@plibdir}/puppet/indirector"
  sh "cp -pr ext/master/lib/puppet/* #{DESTDIR}#{@plibdir}/puppet/"
  #TODO Fix up specs when the specs ship with the puppet packages
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
  sh "cp -p pkg/puppetdb-#{@version}.tar.gz #{temp}/SOURCES"
  sh "cp -p ext/files/#{@name}.spec #{temp}/SPECS"
  sh "rpmbuild #{args} -bs --nodeps #{temp}/SPECS/#{@name}.spec"
  output = `ls #{temp}/SRPMS/*rpm`
  sh "mv #{temp}/SRPMS/*rpm pkg/rpm"
  rm_rf temp
  puts
  puts "Wrote #{`pwd`.strip}/pkg/rpm/#{output.split('/')[-1]}"
end

desc "Build deb package"
task :deb  => [ :package ] do
  temp = `mktemp -d -t tmpXXXXXX`.strip
  mkdir_p temp
  sh "cp -p pkg/puppetdb-#{@version}.tar.gz #{temp}"
  sh "cd #{temp}; tar  -z -x -f #{temp}/puppetdb-#{version}.tar.gz"
  sh "mv #{temp}/puppetdb-#{@version}.tar.gz #{temp}/#{@name}_#{@version}.orig.tar.gz"
  #%x{cd #{temp}/puppetdb-#{@version}; debuild --no-lintian  -uc -us}
  sh "cd #{temp}/puppetdb-#{@version}; debuild --no-lintian  -uc -us"
  mkdir_p "pkg/deb"
  rm_rf "#{temp}/puppetdb-#{@version}"
  sh "mv #{temp}/* pkg/deb"
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
