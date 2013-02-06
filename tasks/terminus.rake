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

