source ENV['GEM_SOURCE'] || "https://rubygems.org"
puppet_branch = ENV['PUPPET_VERSION'] || "latest"
oldest_supported_puppet = "4.0.0"
beaker_version = ENV['BEAKER_VERSION']

def location_for(place, fake_version = nil)
  if place =~ /^(git:[^#]*)#(.*)/
    [fake_version, { :git => $1, :branch => $2, :require => false }].compact
  elsif place =~ /^file:\/\/(.*)/
    ['>= 0', { :path => File.expand_path($1), :require => false }]
  else
    [place, { :require => false }]
  end
end

gem 'facter'
gem 'rake'

group :test do
  # Pinning to work-around an incompatiblity with 2.14 in puppetlabs_spec_helper
  gem 'rspec', '~> 3.1'
  gem 'puppetlabs_spec_helper', :require => false

  case puppet_branch
  when "latest"
    gem 'puppet', ">= #{oldest_supported_puppet}", :require => false
  when "oldest"
    gem 'puppet', oldest_supported_puppet, :require => false
  else
    gem 'puppet', :git => 'git://github.com/puppetlabs/puppet.git',
      :branch => puppet_branch, :require => false
  end

  gem 'mocha', '~> 1.0'
end

group :acceptance do
  if beaker_version
    #use the specified version
    gem 'beaker', *location_for(beaker_version)
  else
    # use the pinned version
    gem 'beaker', '~>2.11'
  end
  # This forces google-api-client to not download retirable 2.0.0 which lacks
  # ruby 1.9.x support.
  gem 'retriable', '~> 1.4'
end
