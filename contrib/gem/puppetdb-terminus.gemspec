# -*- encoding: utf-8 -*-
lib = File.expand_path('../lib', __FILE__)
$LOAD_PATH.unshift(lib) unless $LOAD_PATH.include?(lib)

Gem::Specification.new do |gem|
  gem.name          = "puppetdb-terminus"
  gem.version       = "3.0.0"
  gem.authors       = ["Puppet Labs"]
  gem.email         = ["puppet@puppetlabs.com"]
  gem.description   = "Puppet terminus files to connect to PuppetDB"
  gem.summary       = "Connect Puppet to PuppetDB by setting up a terminus for PuppetDB"
  gem.homepage      = "https://github.com/puppetlabs/puppetdb"
  gem.license       = "Apache-2.0"

  gem.files         = Dir['LICENSE.txt', 'NOTICE.txt', 'README.md', 'puppet/lib/**/*', 'puppet/spec/**/*']
  gem.executables   = gem.files.grep(%r{^bin/}).map{ |f| File.basename(f) }
  gem.test_files    = gem.files.grep(%r{^(test|spec|features)/})
  gem.require_paths = ["lib"]

  gem.add_runtime_dependency 'json'
  gem.add_runtime_dependency 'puppet', ['>= 3.8.1', '<5.0']
end
