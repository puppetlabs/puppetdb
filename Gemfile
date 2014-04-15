source 'https://rubygems.org'

gem 'facter'

group :test do
  # Pinning to work-around an incompatiblity with 2.14 in puppetlabs_spec_helper
  gem 'rspec', '2.13.0'
  gem 'puppetlabs_spec_helper', '0.4.1', :require => false

  gem 'puppet', :require => false

  gem 'activerecord', '2.3.14'
  gem 'sqlite3'

  gem 'mocha', '~> 1.0'

  # Since newer versions of rake are not supported, we pin
  case RUBY_VERSION
  when '1.8.5'
    gem 'rake', '<= 0.8.7'
  when '1.8.7'
    gem 'rake', '<= 10.1.1'
  else
    gem 'rake'
  end
end

group :acceptance do
#  gem 'beaker', '~> 1.7'
  # Temp branch with new ec2 backend
  gem 'beaker', :git => 'git://github.com/kbarber/beaker.git', :branch => 'ticket/master/pdb-554'
end
