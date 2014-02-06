step "Install other dependencies on database" do
  os = test_config[:os_families][database.name]

  case os
  when :redhat, :fedora
    # Our teardown script does some heinous magic with unzip to dig
    # into the puppetdb jar.  Redhat doesn't ship with unzip.
    on database, "yum install -y unzip"
  when :debian
    on database, "apt-get install -y unzip"
  end


  case test_config[:install_type]
    when :git
      case os
      when :debian
        on database, "apt-get install -y --force-yes openjdk-6-jre-headless rake"
      when :redhat
        on database, "yum install -y java-1.6.0-openjdk rubygem-rake"
      when :fedora
        on database, "yum install -y java-1.7.0-openjdk rubygem-rake"
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
