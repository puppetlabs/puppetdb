# We are eval'd in the scope of the acceptance framework's option-parsing
#  code, so we can't use __FILE__ to find our location.  We have access to
#  a variable 'options_file_path', though.
require File.expand_path(File.join(File.dirname(options_file_path), 'common.rb'))

common_options_hash.tap do |my_hash|
    my_hash[:puppetdb_database]      = 'embedded'
    my_hash[:is_puppetserver]        = 'true'                                 if ENV['BEAKER_TYPE'] == 'aio'
    my_hash[:'use-service']          = 'true'                                 if ENV['BEAKER_TYPE'] == 'aio'
    my_hash[:'puppetserver-confdir'] = '/etc/puppetlabs/puppetserver/conf.d'  if ENV['BEAKER_TYPE'] == 'aio'
    my_hash[:puppetservice]          = 'puppetserver'                         if ENV['BEAKER_TYPE'] == 'aio'
end
