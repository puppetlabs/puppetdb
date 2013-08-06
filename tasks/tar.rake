# The tar task for creating a tarball of puppetdb
JAR_FILE_V = "puppetdb-#{@version}-standalone.jar"

# JAR_FILE the constant is defined in Rakefile
#
file JAR_FILE do |t|
  Rake::Task[:uberjar].invoke
end

namespace :package do
  desc "Create source tarball"
  task :tar => [ :package ]
end

desc "Create a source tar archive"
task :package => [ :clobber, JAR_FILE, :template  ] do
  temp = `mktemp -d -t tmpXXXXXX`.strip
  workdir = File.join(temp, "#{@name}-#{@version}")
  mkdir_p workdir
  FileList[ "tasks", "ext", "*.md", JAR_FILE, "documentation", "Rakefile" ].each do |f|
    sh "cp -pr #{f} #{workdir}"
  end

  # Lay down version file for later reading
  File.open(File.join(workdir,'version'), File::CREAT|File::TRUNC|File::RDWR, 0644) do |f|
    f.puts @version
  end
  mv "#{workdir}/ext/files/debian", "#{workdir}/ext"
  cp_pr "puppet", "#{workdir}/ext/master"
  mkdir_p "pkg"
  pkg_dir = File.expand_path(File.join(".", "pkg"))
  sh "cd #{temp}; tar --exclude=.gitignore --exclude=ext/packaging -zcf '#{pkg_dir}/#{@name}-#{@version}.tar.gz' #{@name}-#{@version}"
  rm_rf temp
  puts
  puts "Wrote #{`pwd`.strip}/pkg/#{@name}-#{@version}"
end

