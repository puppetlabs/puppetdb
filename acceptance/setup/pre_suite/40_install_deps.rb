unless (test_config[:skip_presuite_provisioning])

  step "Update CA cerificates" do
    os = test_config[:os_families][master.name]
    case os
    when :redhat
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
  elsif is_el7
    step "Install PostgreSQL el7 repo" do
      on master, "yum install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-7-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
    end
  elsif is_el9
    step "Install PostgreSQL el8 repo" do
      on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-9-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
    end
  elsif is_bionic
    # bionic is EOL, so get postgresql from the archive
    on master, 'echo "deb https://apt-archive.postgresql.org/pub/repos/apt bionic-pgdg main" >> /etc/apt/sources.list'
    on master, 'curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -'
    on master, 'apt update'
  end
end
