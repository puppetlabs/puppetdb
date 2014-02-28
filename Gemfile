source 'https://rubygems.org'

gem 'facter'

group :test do
  # Pinning to work-around an incompatiblity with 2.14 in puppetlabs_spec_helper
  gem 'rspec', '2.13.0'
  gem 'puppetlabs_spec_helper', '0.4.1', :require => false

  gem 'puppet', :require => false

  gem 'mocha', '~> 1.0'

  # Since newer versions of rake are not supported, we pin
  if RUBY_VERSION == '1.8.5'
    gem 'rake', '<= 0.8.7'
  end
end

group :acceptance do
  gem 'beaker', '~> 1.7'
end
