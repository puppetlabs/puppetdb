repo_config_dir = 'tmp/repo_configs'

if (test_config[:install_type] == :package) \
   && test_config[:package_build_version] \
   && !(test_config[:skip_presuite_provisioning])
then
  # do not install the dev_repo if a package_build_version has not been specified.
  databases.each do |database|
    install_puppetlabs_dev_repo database, 'puppetdb', oldest_supported,
      repo_config_dir, options

    install_puppetlabs_dev_repo database, 'puppetdb', test_config[:package_build_version],
      repo_config_dir, options
  end
end
