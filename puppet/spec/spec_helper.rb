dir = File.expand_path(File.dirname(__FILE__))
$LOAD_PATH.unshift File.join(dir, "../lib")
# Maybe puppetlabs_spec_helper is in a directory next to puppetdb. If not, we
# don't fail any worse than we already would.
$LOAD_PATH.push File.join(dir, "../../../puppetlabs_spec_helper")

require 'puppet_spec_helper'
