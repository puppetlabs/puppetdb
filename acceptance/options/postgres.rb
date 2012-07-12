# We are eval'd in the scope of the acceptance framework's option-parsing
#  code, so we can't use __FILE__ to find our location.  We have access to
#  a variable 'options_file_path', though.
require File.expand_path(File.join(File.dirname(options_file_path), 'common.rb'))

common_options_hash.merge({
    :puppetdb_database => 'postgres',
})
