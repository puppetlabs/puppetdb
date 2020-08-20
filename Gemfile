gemfile_home = File.dirname(__FILE__)

source ENV['GEM_SOURCE'] || "https://rubygems.org"
oldest_supported_puppet = "5.0.0"
beaker_version = ENV['BEAKER_VERSION']

begin
  puppet_ref = File.read(gemfile_home + '/ext/test-conf/puppet-ref-requested').strip
rescue Errno::ENOENT
  puppet_ref = File.read(gemfile_home + '/ext/test-conf/puppet-ref-default').strip
end

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
gem 'packaging', *location_for(ENV['PACKAGING_LOCATION'] || '~> 0.99')

group :test do
  # Add test-unit for ruby 2.2+ support (has been removed from stdlib)
  gem 'test-unit'

  # Pinning for Ruby 1.9.3 support
  gem 'json_pure', '~> 1.8'
  # Pinning for Ruby < 2.2.0 support
  gem 'activesupport', '~> 4.2'

  # addressable 2.5 requires public_suffix, which requires ruby 2.
  gem 'addressable', '< 2.5.0'

  # Pinning to work-around an incompatiblity with 2.14 in puppetlabs_spec_helper
  gem 'rspec', '~> 3.1'
  gem 'puppetlabs_spec_helper', '0.10.3', :require => false

  # docker-api 1.32.0 requires ruby 2.0.0
  gem 'docker-api', '1.31.0'

  case puppet_ref
  when "latest"
    gem 'puppet', ">= #{oldest_supported_puppet}", :require => false
  when "oldest"
    gem 'puppet', oldest_supported_puppet, :require => false
  else
    gem 'puppet', :git => 'https://github.com/puppetlabs/puppet.git',
      :ref => puppet_ref, :require => false
  end

  gem 'mocha', '~> 1.0'
end

group :development do
  gem 'httparty'
end

# This is a workaround for a bug in bundler, where it likes to look at ruby
# version deps regardless of what groups you want or not. This lets us
# conditionally shortcut evaluation entirely.
if ENV['NO_ACCEPTANCE'] != 'true'
  group :acceptance do
    if beaker_version
      #use the specified version
      gem 'beaker', *location_for(beaker_version)
    else
      # use the pinned version
      gem 'beaker', '~> 4.1'
    end
  end
  gem 'beaker-hostgenerator', '~> 1.2.6'
  gem 'beaker-abs', *location_for(ENV['BEAKER_ABS_VERSION'] || '~> 0.2')
  gem 'beaker-vmpooler', *location_for(ENV['BEAKER_VMPOOLER_VERSION'] || "~> 1.3")
  gem 'beaker-puppet', '~> 1.0'
end
