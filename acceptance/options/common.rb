def common_options_hash()
  dir = File.expand_path(File.join(File.dirname(__FILE__), '..'))

  {
    :helper    => [File.join(dir, 'helper.rb')],
    :pre_suite => [File.join(dir, 'setup', 'early'),
                   File.join(dir, 'setup', 'pre_suite')]
  }
end
