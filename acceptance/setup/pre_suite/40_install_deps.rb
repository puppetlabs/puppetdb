unless (test_config[:skip_presuite_provisioning])
  step "Install other dependencies on database" do
    databases.each do |database|
      os = test_config[:os_families][database.name]

      if test_config[:install_type] == :git then
        case os
        when :debian
          on database, "apt-get install -y --force-yes openjdk-7-jre-headless rake unzip"
        when :redhat
          on database, "yum install -y java-1.7.0-openjdk rubygem-rake unzip"
        when :fedora
          on database, "yum install -y java-1.7.0-openjdk rubygem-rake unzip"
        else
          raise ArgumentError, "Unsupported OS '#{os}'"
        end

        step "Install lein on the PuppetDB server" do
          which_result = on database, "which lein", :acceptable_exit_codes => [0,1]
          needs_lein = which_result.exit_code == 1
          if (needs_lein)
            on database, "curl --tlsv1 -Lk https://raw.github.com/technomancy/leiningen/stable/bin/lein -o /usr/local/bin/lein"
            on database, "chmod +x /usr/local/bin/lein"
            on database, "LEIN_ROOT=true lein"
          end
        end
      end

      # This works around the fact that puppetlabs-concat 1.2.3 requires a ruby in
      # the normal path, here we work around this for AIO by just installing one
      # on the database node that needs it.
      #
      # https://github.com/puppetlabs/puppetlabs-concat/blob/1.2.3/files/concatfragments.rb#L1
      #
      # This can be removed with concat 2.x onces its re-released, as this doesn't
      # need an external ruby vm, it uses proper types & providers.
      if options[:type] == 'aio' && os == :debian &&
        database['platform'] !~ /ubuntu/ then
        step "Install rubygems on database for AIO on Debian" do
          on database, "apt-get install -y rubygems ruby-dev"
        end
      end
    end

    step "Install rubygems and sqlite3 on master" do
      os = test_config[:os_families][master.name]

      case os
      when :redhat
        case master['platform']
        when /^el-5/
          on master, "yum install -y rubygems sqlite-devel rubygem-activerecord ruby-devel.x86_64"
          on master, "gem install sqlite3"
        when /^el-6/
          on master, "yum install -y rubygems ruby-sqlite3 rubygem-activerecord"
        else
          # EL7 very much matches what Fedora 20 uses
          on master, "yum install -y rubygems rubygem-sqlite3"
          on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"
        end
      when :fedora
        # This was really set with Fedora 20 in mind, later versions might differ
        on master, "yum install -y rubygems rubygem-sqlite3"
        on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"
      when :debian
        case master['platform']
        when /^ubuntu-10.04/
          # Ubuntu 10.04 has rubygems 1.3.5 which is known to not be reliable, so therefore
          # we skip.
        when /^ubuntu-14.04/
          on master, "apt-get install -y ruby ruby-dev libsqlite3-dev build-essential"
          # this is to get around the activesupport dependency on Ruby 1.9.3 for
          # Ubuntu 12.04. We can remove it when we drop support for 1.8.7.
          on master, "gem install i18n -v 0.6.11"
          on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"
          on master, "gem install sqlite3 -v 1.3.9 --no-ri --no-rdoc -V --backtrace"
        else
          on master, "apt-get install -y rubygems ruby-dev libsqlite3-dev build-essential"
          # this is to get around the activesupport dependency on Ruby 1.9.3 for
          # Ubuntu 12.04. We can remove it when we drop support for 1.8.7.
          on master, "gem install i18n -v 0.6.11"
          on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"
          on master, "gem install sqlite3 -v 1.3.9 --no-ri --no-rdoc -V --backtrace"
        end
      else
        raise ArgumentError, "Unsupported OS: '#{os}'"
      end

      # Make sure there isn't a gemrc file, because that could ruin our day.
      on master, "rm -f ~/.gemrc"
    end
  end
end
