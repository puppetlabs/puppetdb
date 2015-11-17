# We are eval'd in the scope of the acceptance framework's option-parsing
#  code, so we can't use __FILE__ to find our location.  We have access to
#  a variable 'options_file_path', though.
require File.expand_path(File.join(File.dirname(options_file_path), 'common.rb'))

common_options_hash.tap do |my_hash|
  # These config options are used by beaker and not in our acceptance helpers
  my_hash[:is_puppetserver]        = 'true'
  my_hash[:'use-service']          = 'true'
  my_hash[:'puppetserver-confdir'] = '/etc/puppetlabs/puppetserver/conf.d'
  my_hash[:puppetservice]          = 'puppetserver'
end
