
require 'rake'
require 'rake/packagetask'

def version
# This ugly bit removes the gSHA1 portion of the describe as that causes failing tests
  %x{git describe}.gsub('-', '.').split('.')[0..3].join('.').to_s.gsub('v', '')
end

task :default => [ :package ]

desc "Build the uberjar"
task :uberjar => [  ] do
  if `which lein`
    sh "lein uberjar"
  else
    puts "You need lein on your system"
    exit 1
  end
end

Rake::PackageTask.new("grayskull", version)  do |t|
  t.need_tar_gz = true
  t.package_files.include([ "*.md", "*.jar", "spec/**", "ext/**", "module/**/**"  ] )
end

task :package => [:uberjar ] do
end

task :clean do
  sh  "rm -f grayskull*.jar"
  sh  "rm -f *.tar.gz"
  sh  "rm -rf pkg"
end
