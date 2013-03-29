test_config = PuppetDBExtensions.config

step "Install other dependencies on database" do
  os = test_config[:os_families][database.name]

  case os
  when :redhat
    # Our teardown script does some heinous magic with unzip to dig
    #  into the puppetdb jar.  Redhat doesn't ship with unzip.
    on database, "yum install -y unzip"
  when :debian
    on database, "apt-get install unzip"
  end


  case test_config[:install_type]
    when :git
      case os
      when :debian
        on database, "apt-get install -y --force-yes openjdk-6-jre-headless"
      when :redhat
        on database, "yum install -y java-1.6.0-openjdk rubygem-rake"
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
  when :redhat
    on master, "yum install -y rubygems sqlite-devel"
    on master, "gem install sqlite3"
  when :debian
    on master, "apt-get install -y rubygems libsqlite3-ruby"
  else
    raise ArgumentError, "Unsupported OS '#{os}'"
  end

  # Make sure there isn't a gemrc file, because that could ruin our day.
  on master, "rm ~/.gemrc"
end
