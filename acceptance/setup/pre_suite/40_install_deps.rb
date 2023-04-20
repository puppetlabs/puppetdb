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

  # Unfortunately we need jammy to bring a workable version of ssl-cert into bionic
  if is_bionic
    step 'Update Ubuntu 18 package repo' do
      # Install jammy repos so we can pull in its ssl-cert
      on master, "echo 'deb http://archive.ubuntu.com/ubuntu/ jammy main restricted universe multiverse' > /etc/apt/sources.list.d/jammy.list"
      on master, "echo 'deb-src http://archive.ubuntu.com/ubuntu/ jammy main restricted universe multiverse' >> /etc/apt/sources.list.d/jammy.list"
      on master, 'apt-get update'
      on master, 'apt-get install -y -t jammy ssl-cert'

      # Once we have jammy's ssl-cert get rid of jammy packages to avoid unintentially pulling in other packages
      on master, 'rm /etc/apt/sources.list.d/jammy.list'
      on master, 'apt-get update'
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
