source 'https://rubygems.org'

gem 'facter'

group :test do
  # Pinning to work-around an incompatiblity with 2.14 in puppetlabs_spec_helper
  gem 'rspec', '2.13.0'
  gem 'puppetlabs_spec_helper', '0.4.1', :require => false

  gem 'puppet', :require => false

  gem 'puppet_acceptance', :git => 'git://github.com/puppetlabs/puppet-acceptance.git', :require => false
end
