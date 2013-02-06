# Template task to handle all of puppetdb's erb templates
#
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

   # developer utility files for redhat
   mkdir_p "ext/files/dev/redhat"
   erb "ext/templates/dev/redhat/redhat_dev_preinst.erb", "ext/files/dev/redhat/redhat_dev_preinst"
   erb "ext/templates/dev/redhat/redhat_dev_postinst.erb", "ext/files/dev/redhat/redhat_dev_postinst"
end
