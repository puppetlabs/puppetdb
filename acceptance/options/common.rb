def common_options_hash()
  puppetdb_acceptance_dir = File.expand_path(File.join(File.dirname(__FILE__), '..'))

  def get_install_mode()
    mode = :install
    if (ENV['PUPPETDB_INSTALL_MODE'])
      mode = ENV['PUPPETDB_INSTALL_MODE'].intern
      puts "Found environment variable 'PUPPETDB_INSTALL_MODE' with value '#{ENV['PUPPETDB_INSTALL_MODE']}'; setting puppetdb options[:puppetdb_install_mode] to value '#{mode.inspect}'"
    end
    unless [:install, :upgrade].include?(mode)
      raise ArgumentError, "Unsupported puppetdb install mode '#{mode}'"
    end
    mode
  end

  {
      :helper                 => File.join(puppetdb_acceptance_dir, "helper.rb"),
      :setup_dir              => File.join(puppetdb_acceptance_dir, "setup"),
      :puppetdb_install_mode  => get_install_mode(),
  }
end
