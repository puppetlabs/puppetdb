# Template task to handle all of puppetdb's erb templates
#
task :template => [ :clean ] do
  shared_templates = {
    # files for deb and rpm
    "ext/templates/log4j.properties.erb"    => "ext/files/log4j.properties",
    "ext/templates/config.ini.erb"          => "ext/files/config.ini",
    "ext/templates/jetty.ini.erb"           => "ext/files/jetty.ini",
    "ext/templates/repl.ini.erb"            => "ext/files/repl.ini",
    "ext/templates/database.ini.erb"        => "ext/files/database.ini",
    "ext/templates/puppetdb-foreground.erb" => "ext/files/puppetdb-foreground",
    "ext/templates/puppetdb-import.erb"     => "ext/files/puppetdb-import",
    "ext/templates/puppetdb-export.erb"     => "ext/files/puppetdb-export",
    "ext/templates/puppetdb-anonymize.erb"  => "ext/files/puppetdb-anonymize",
    "ext/templates/puppetdb.erb"            => "ext/files/puppetdb",
    "ext/templates/puppetdb-legacy.erb"     => "ext/files/puppetdb-legacy",
    "ext/templates/init_debian.erb"         => "ext/files/#{@name}.debian.init",
    "ext/templates/puppetdb-env.erb"        => "ext/files/puppetdb.env"
  }

  deb_templates = {
    "ext/templates/init_debian.erb"           => "ext/files/debian/#{@name}.init",
    "ext/templates/puppetdb_default.erb"      => "ext/files/debian/#{@name}.default",
    "ext/templates/deb/control.erb"           => "ext/files/debian/control",
    "ext/templates/deb/prerm.erb"             => "ext/files/debian/#{@name}.prerm",
    "ext/templates/deb/postrm.erb"            => "ext/files/debian/#{@name}.postrm",
    "ext/templates/deb/base.install.erb"      => "ext/files/debian/#{@name}.install",
    "ext/templates/deb/terminus.install.erb"  => "ext/files/debian/#{@name}-terminus.install",
    "ext/templates/deb/rules.erb"             => "ext/files/debian/rules",
    "ext/templates/deb/changelog.erb"         => "ext/files/debian/changelog",
    "ext/templates/deb/preinst.erb"           => "ext/files/debian/#{@name}.preinst",
    "ext/templates/deb/postinst.erb"          => "ext/files/debian/#{@name}.postinst",
    "ext/templates/logrotate.erb"             => "ext/files/debian/#{@name}.logrotate",
  }

  rpm_other_templates = {
    "ext/redhat/#{@name}.spec.erb"                      => "ext/files/#{@name}.spec",
    "ext/templates/logrotate.erb"                       => "ext/files/puppetdb.logrotate",
    "ext/templates/init_redhat.erb"                     => "ext/files/puppetdb.redhat.init",
    "ext/templates/init_suse.erb"                       => "ext/files/puppetdb.suse.init",
    "ext/templates/puppetdb_default.erb"                => "ext/files/puppetdb.default",
    "ext/templates/dev/redhat/redhat_dev_preinst.erb"   => "ext/files/dev/redhat/redhat_dev_preinst",
    "ext/templates/dev/redhat/redhat_dev_postinst.erb"  => "ext/files/dev/redhat/redhat_dev_postinst",
    "ext/templates/init_openbsd.erb"                    => "ext/files/puppetdb.openbsd.init",
    "ext/templates/puppetdb.service.erb"                => "ext/files/systemd/#{@name}.service"
  }

  # Set up shared files
  mkdir_p "ext/files/debian"
  shared_templates.each do |template, target|
    Pkg::Util::File.erb_file(template, target, false, :binding => binding)
  end
  chmod 0700, "ext/files/puppetdb-foreground"
  chmod 0700, "ext/files/puppetdb-import"
  chmod 0700, "ext/files/puppetdb-export"
  chmod 0700, "ext/files/puppetdb-anonymize"
  cp_p "ext/templates/puppetdb-ssl-setup", "ext/files"
  chmod 0700, "ext/files/puppetdb-ssl-setup"
  chmod 0700, "ext/files/puppetdb"
  chmod 0700, "ext/files/puppetdb-legacy"

  # Set up deb files
  deb_templates.each do |template, target|
    Pkg::Util::File.erb_file(template, target, false, :binding => binding)
  end
  chmod 0755, "ext/files/debian/rules"
  cp_pr FileList["ext/templates/deb/*"].reject {|f| File.extname(f) == ".erb" }, "ext/files/debian"


  # Set up rpm files, utilities, and misc other
  mkdir_p "ext/files/dev/redhat"
  mkdir_p "ext/files/systemd"
  rpm_other_templates.each do |template, target|
    Pkg::Util::File.erb_file(template, target, false, :binding => binding)
  end
  chmod 0644, "ext/files/systemd/#{@name}.service"
end
