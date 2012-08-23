test_config = PuppetDBExtensions.config

step "Install other dependencies" do
  os = test_config[:os_families][database.name]

  case os
  when :redhat
    # Our teardown script does some heinous magic with unzip to dig
    #  into the puppetdb jar.  Redhat doesn't ship with unzip.
    on database, "yum install -y unzip"
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
          on database, "curl -k https://raw.github.com/technomancy/leiningen/1.7.1/bin/lein -o /usr/local/bin/lein"
          on database, "chmod +x /usr/local/bin/lein"
          on database, "LEIN_ROOT=true lein"
        end
      end
  end
end
