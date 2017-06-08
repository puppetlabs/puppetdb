install_opts = options.merge( { :dev_builds_repos => ["PC1"] })
repo_config_dir = 'tmp/repo_configs'
if (test_config[:install_type] == :package and test_config[:package_build_version] and not test_config[:skip_presuite_provisioning])
  # do not install the dev_repo if a package_build_version has not been specified.
  databases.each do |database|
    install_puppetlabs_dev_repo database, 'puppetdb', test_config[:package_build_version],
                                repo_config_dir, install_opts
  end
end
