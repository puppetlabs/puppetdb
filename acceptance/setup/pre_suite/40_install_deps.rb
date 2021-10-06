unless (test_config[:skip_presuite_provisioning])

  step "Update CA cerificates" do
    os = test_config[:os_families][master.name]
    case os
    when :redhat
      if is_el6
        # workaround for old ca-certificates package, trick
        # yum into looking for a newer redhat 6.y version's package
        on master, "rm -f /etc/yum.repos.d/localmirror-extras.repo /etc/yum.repos.d/localmirror-optional.repo &&  sed -i 's/68/610/' /etc/yum.repos.d/localmirror-os.repo"
      end
      on master, "yum install -y ca-certificates"
    when :fedora
      on master, "yum install -y ca-certificates"
    when :debian
      on master, "apt-get install -y ca-certificates libgnutls30"
      on master, "apt-get update"
    end
  end

  if is_el8
    # work around for testing on rhel8 and the repos on the image not finding the pg packages it needs
    step "Install PostgreSQL manually" do
      on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
      on master, "dnf -qy module disable postgresql"
    end
  end
end
