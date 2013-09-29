PROXY_URL = "http://modi.puppetlabs.lan:3128"

def setup_apt_proxy()
  step "Configure apt to use local http proxy" do
    apt_conf_file_path = "/etc/apt/apt.conf.d/99apt-http-proxy"
    apt_conf_file_content = <<-EOS
// Configure apt to use a local http proxy
Acquire::http::Proxy "#{PROXY_URL}";
    EOS

    create_remote_file(database, apt_conf_file_path, apt_conf_file_content)
  end
end

def setup_yum_proxy()
  step "Configure yum to use local http proxy" do

    existing_yumconf = on(database, "cat /etc/yum.conf").stdout
    new_yumconf_lines = []
    existing_yumconf.each_line do |line|
      # filter out existing proxy line if there is one.
      unless line =~ /^\s*proxy\s*=/
        new_yumconf_lines << line
      end
    end
    new_yumconf_lines << "proxy=#{PROXY_URL}\n"
    on(database, "mv /etc/yum.conf /etc/yum.conf.bak-puppet_acceptance")
    create_remote_file(database, "/etc/yum.conf", new_yumconf_lines.join)
  end
end

def setup_maven_proxy()
  on(database, "mkdir -p /root/.m2")

  m2_settings_path = "/root/.m2/settings.xml"
  m2_settings_content = <<-EOS
<settings>
    <proxies>
        <proxy>
            <active>true</active>
            <protocol>http</protocol>
            <host>modi.puppetlabs.lan</host>
            <port>3128</port>
        </proxy>
    </proxies>
</settings>
  EOS

  create_remote_file(database, m2_settings_path, m2_settings_content)
end


if (test_config[:use_proxies])

  step "Configure package manager to use local http proxy" do
    # TODO: this should probably run on every host, not just on the database host,
    #  and it should probably be moved into the main acceptance framework instead
    #  of being used only for our project.
    case test_config[:os_families][database.name]
    when :debian
      setup_apt_proxy()
    when :redhat
      setup_yum_proxy()
    else
      raise ArgumentError, "Unsupported OS family: '#{config[:os_families][database.name]}'"
    end
  end


  step "Configure maven to use local http proxy" do
    setup_maven_proxy
  end

else
  Beaker::Log.notify "Skipping proxy setup ; test run configured not to use proxies via :puppetdb_use_proxies setting."
end
