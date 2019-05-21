unless (test_config[:skip_presuite_provisioning])

  step "Update CA cerificates" do
    os = test_config[:os_families][master.name]
    case os
    when :redhat
      on master, "yum install -y ca-certificates"
    when :fedora
      on master, "yum install -y ca-certificates"
    when :debian
      on master, "apt-get install -y ca-certificates"
    end
  end

  step "Ensure python 2 is available" do
    master_os = test_config[:os_families][master.name]
    case master_os
    when :debian
      on master, "apt-get -y install python"
    end

    databases.each do |database|
      os = test_config[:os_families][database.name]
      case os
      when :debian
        on database, "apt-get -y install python"
      end
    end
  end

  step "Install other dependencies on database" do
    databases.each do |database|
      os = test_config[:os_families][database.name]

      ### ensure nss is up to date
      case os
      when :debian
        on database, "apt-get install -y --force-yes libnss3"
      when :redhat
        on database, "yum update -y nss"
      when :fedora
        on database, "yum update -y nss"
      else
        raise ArgumentError, "Unsupported OS '#{os}'"
      end

      if test_config[:install_type] == :git then
        case os
        when :debian
          if database['platform'].variant == 'debian' &&
             database['platform'].version == '8'
            on database, "apt-get install -y rake unzip"
            on database, "apt-get install -y openjdk-8-jre-headless"
          else
            on database, "apt-get install -y rake unzip openjdk-8-jre-headless"
          end
        when :redhat
          on database, "yum install -y java-1.8.0-openjdk rubygem-rake unzip"
        when :fedora
          on database, "yum install -y java-1.8.0-openjdk rubygem-rake unzip"
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
        on master, "yum update -y nss"
        case master['platform']
        when /^el-5/
          on master, "yum install -y rubygems sqlite-devel rubygem-activerecord ruby-devel.x86_64"
          on master, "gem install sqlite3"
        when /^el-6/
          on master, "yum install -y rubygems ruby-sqlite3 rubygem-activerecord"
        when /^el-7/
          # EL7 very much matches what Fedora 20 uses
          on master, "yum install -y rubygems rubygem-sqlite3"
          on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"
        else
          # rubygem-sqlite3 doesn't exist on rhel-8
          on master, "yum install -y rubygems"
          on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"

          # work around for testing on rhel8 and the repos on the image not finding the pg packages it needs
          on master, "sed -i 's/gpgcheck=1/gpgcheck=0/g' /etc/dnf/dnf.conf"
          on master, "dnf config-manager --add-repo https://download.postgresql.org/pub/repos/yum/9.6/redhat/rhel-8-x86_64/"
          on master, "dnf clean all"
          on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/9.6/redhat/rhel-8-x86_64/postgresql96-9.6.13-1PGDG.rhel8.x86_64.rpm"
          on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/9.6/redhat/rhel-8-x86_64/postgresql96-server-9.6.13-1PGDG.rhel8.x86_64.rpm"
        end
      when :fedora
        # This was really set with Fedora 20 in mind, later versions might differ
        on master, "yum install -y rubygems rubygem-sqlite3"
        on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"
        on master, "yum update -y nss"
      when :debian
        on master, "apt-get install -y libnss3"
        case master['platform']
        when /^ubuntu-10.04/
          # Ubuntu 10.04 has rubygems 1.3.5 which is known to not be reliable, so therefore
          # we skip.
        when /^ubuntu-12.04/
          on master, "apt-get install -y rubygems ruby-dev libsqlite3-dev build-essential"
          # this is to get around the activesupport dependency on Ruby 1.9.3 for
          # Ubuntu 12.04. We can remove it when we drop support for 1.8.7.
          on master, "gem install i18n -v 0.6.11"
          on master, "gem install activerecord -v 3.2.17 --no-ri --no-rdoc -V --backtrace"
          on master, "gem install sqlite3 -v 1.3.9 --no-ri --no-rdoc -V --backtrace"
        else
          if (master['platform'] =~ /^debian-8/)
            # Required to address failure during i18n install:
            # /usr/lib/x86_64-linux-gnu/ruby/2.1.0/openssl.so: symbol
            # SSLv2_method, version OPENSSL_1.0.0 not defined in file
            # libssl.so.1.0.0 with link time reference -
            # /usr/lib/x86_64-linux-gnu/ruby/2.1.0/openssl.so
            on master, "apt-get install -y --force-yes openssl"
          end

          on master, "apt-get install -y --force-yes ruby ruby-dev libsqlite3-dev build-essential"
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
