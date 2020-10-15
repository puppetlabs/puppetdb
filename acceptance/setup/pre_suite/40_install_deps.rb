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

  if is_rhel8
    # work around for testing on rhel8 and the repos on the image not finding the pg packages it needs
    step "Install PostgreSQL manually" do
      on master, "sed -i 's/gpgcheck=1/gpgcheck=0/g' /etc/dnf/dnf.conf"
      on master, "dnf config-manager --add-repo https://download.postgresql.org/pub/repos/yum/9.6/redhat/rhel-8-x86_64/"
      on master, "dnf clean all"
      on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/9.6/redhat/rhel-8-x86_64/postgresql96-9.6.16-2PGDG.rhel8.x86_64.rpm"
      on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/9.6/redhat/rhel-8-x86_64/postgresql96-server-9.6.16-2PGDG.rhel8.x86_64.rpm"
      on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/9.6/redhat/rhel-8-x86_64/postgresql96-contrib-9.6.16-2PGDG.rhel8.x86_64.rpm"
    end
  end
end
