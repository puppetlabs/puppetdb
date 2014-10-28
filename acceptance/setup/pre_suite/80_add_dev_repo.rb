if (test_config[:install_type] == :package)
  databases.each do |database|
    os = test_config[:os_families][database.name]
    db_facts = facts(database.name)

    sed_cmd = "sed 's/#{Regexp.escape(test_config[:package_build_host])}/#{Regexp.escape(test_config[:package_repo_host])}/'"

    step "Add development repository on PuppetDB server" do
      case os
      when :debian
        result = on database, "lsb_release -sc"
        deb_flavor = result.stdout.chomp
        apt_list_url = "#{test_config[:package_repo_url]}/repo_configs/deb/pl-puppetdb-#{test_config[:git_ref]}-#{deb_flavor}.list"
        apt_list_file_path = "/etc/apt/sources.list.d/puppetdb-prerelease.list"
        on database, "curl \"#{apt_list_url}\" | #{sed_cmd} > #{apt_list_file_path}"
        result = on database, "cat #{apt_list_file_path}"
        Log.notify("APT LIST FILE CONTENTS:\n#{result.stdout}\n")
        on database, "apt-get update"
      when :redhat
        el_version = db_facts["operatingsystemmajrelease"]
        yum_repo_url = "#{test_config[:package_repo_url]}/repo_configs/rpm/pl-puppetdb-#{test_config[:git_ref]}-el-#{el_version}-x86_64.repo"
        yum_repo_file_path = "/etc/yum.repos.d/puppetlabs-prerelease.repo"
        on database, "curl \"#{yum_repo_url}\" | #{sed_cmd} > #{yum_repo_file_path}"

        result = on database, "cat #{yum_repo_file_path}"
        Log.notify("Yum REPO DEFINITION:\n\n#{result.stdout}\n\n")
      when :fedora
        version = db_facts["operatingsystemrelease"]
        yum_repo_url = "#{test_config[:package_repo_url]}/repo_configs/rpm/pl-puppetdb-#{test_config[:git_ref]}-fedora-f#{version}-x86_64.repo"
        yum_repo_file_path = "/etc/yum.repos.d/puppetlabs-prerelease.repo"
        on database, "curl \"#{yum_repo_url}\" | #{sed_cmd} > #{yum_repo_file_path}"

        result = on database, "cat #{yum_repo_file_path}"
        Log.notify("Yum REPO DEFINITION:\n\n#{result.stdout}\n\n")
      else
        raise ArgumentError, "Unsupported OS '#{os}'"
      end
    end
  end
end
