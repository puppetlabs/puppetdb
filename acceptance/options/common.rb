def common_options_hash()
  puppetdb_acceptance_dir = File.expand_path(File.join(File.dirname(__FILE__), '..'))

  {
      :helper                 => File.join(puppetdb_acceptance_dir, "helper.rb"),
      :setup_dir              => File.join(puppetdb_acceptance_dir, "setup"),
  }
end
