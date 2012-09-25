# -*- encoding: utf-8 -*-
lib = File.expand_path('../lib', __FILE__)
$LOAD_PATH.unshift(lib) unless $LOAD_PATH.include?(lib)

Gem::Specification.new do |gem|
  gem.name          = "puppetdb-terminus"
  gem.version       = "1.0"
  gem.authors       = ["Puppet Labs"]
  gem.email         = ["puppet@puppetlabs.com"]
  gem.description   = "Centralized Puppet Storage"
  gem.summary       = "PuppetDB is a Puppet data warehouse; it manages storage and retrieval of all platform-generated data, such as catalogs, facts, reports"
  gem.homepage      = "https://github.com/puppetlabs/puppetdb"

  gem.files         = `git ls-files`.split($/)
  gem.executables   = gem.files.grep(%r{^bin/}).map{ |f| File.basename(f) }
  gem.test_files    = gem.files.grep(%r{^(test|spec|features)/})
  gem.require_paths = ["lib"]
end
