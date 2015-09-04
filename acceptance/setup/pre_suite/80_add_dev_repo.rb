install_opts = options.merge( { :dev_builds_repos => ["PC1"] })
repo_config_dir = 'tmp/repo_configs'
if (test_config[:install_type] == :package and not test_config[:skip_presuite_provisioning])
  databases.each do |database|
    install_puppetlabs_dev_repo database, 'puppetdb', test_config[:package_build_version],
                                repo_config_dir, install_opts
  end
end
