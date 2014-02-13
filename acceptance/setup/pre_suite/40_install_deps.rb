step "Install other dependencies on database" do
  os = test_config[:os_families][database.name]
  db_facts = facts(database.name)

  use_our_jdk = ((db_facts["osfamily"] == "Debian") and
                 (db_facts["operatingsystemmajrelease"] == "6" or
                  db_facts["operatingsystemrelease"] == "10.04"))

  # Install our JDK repository with a JDK 7 for Debian 6 and Ubuntu 10.04
  # and install the oracle jdk
  if use_our_jdk then
    create_remote_file database, '/etc/apt/sources.list.d/jpkg.list', <<-REPO
# Oracle JDK Packages
deb http://s3-us-west-2.amazonaws.com/puppetdb-jdk/jpkg/ pljdk main
    REPO
    # Import GPG key
    on database, "gpg --keyserver keys.gnupg.net --recv-keys B8615A77BBBFA17C"
    on database, "gpg -a --export B8615A77BBBFA17C | apt-key add -"
    on database, "apt-get update"
  end

  if test_config[:install_type] == :git then
    case os
    when :debian
      if use_our_jdk then
        # Use our jdk
        on database, "apt-get install -y --force-yes oracle-j2sdk1.7 rake unzip"
      else
        # Other debians have a JDK 7 already, just use that
        on database, "apt-get install -y --force-yes openjdk-7-jre-headless rake unzip"
      end
    when :redhat
      on database, "yum install -y java-1.6.0-openjdk rubygem-rake unzip"
    when :fedora
      on database, "yum install -y java-1.7.0-openjdk rubygem-rake unzip"
    else
      raise ArgumentError, "Unsupported OS '#{os}'"
    end

    step "Install lein on the PuppetDB server" do
      which_result = on database, "which lein", :acceptable_exit_codes => [0,1]
      needs_lein = which_result.exit_code == 1
      if (needs_lein)
        on database, "curl -k https://raw.github.com/technomancy/leiningen/preview/bin/lein -o /usr/local/bin/lein"
        on database, "chmod +x /usr/local/bin/lein"
        on database, "LEIN_ROOT=true lein"
      end
    end
  end
end

step "Install rubygems and sqlite3 on master" do
  os = test_config[:os_families][master.name]

  case os
  when :redhat, :fedora
    if master['platform'].include? 'el-5'
      on master, "yum install -y rubygems sqlite-devel rubygem-activerecord ruby-devel.x86_64"
      on master, "gem install sqlite3"
    else
      on master, "yum install -y rubygems ruby-sqlite3 rubygem-activerecord"
    end
  when :debian
    on master, "apt-get install -y rubygems libsqlite3-ruby"
    # this is to work around the absense of a decent package in lucid
    on master, "gem install activerecord -v 2.3.17 --no-ri --no-rdoc -V --backtrace"
  else
    raise ArgumentError, "Unsupported OS '#{os}'"
  end

  # Make sure there isn't a gemrc file, because that could ruin our day.
  on master, "rm -f ~/.gemrc"
end
