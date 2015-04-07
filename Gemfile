source ENV['GEM_SOURCE'] || "https://rubygems.org"
puppet_branch = ENV['puppet_branch'] || "latest"
oldest_supported_puppet = "3.5.1"
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

case RUBY_VERSION
when '1.8.7'
  gem 'rake', '<= 10.1.1'
  # activesupport calls in the latest i18n, which drops 1.8.7. This pins to
  # a lower version
  gem 'i18n', '~> 0.6.11'
else
  gem 'rake'
end

group :test do
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

  # Since newer versions of rake are not supported, we pin
  case RUBY_VERSION
  when '1.8.7'
    # No activerecord or sqlite for you
  else
    gem 'activerecord', '~> 3.2'
    gem 'sqlite3'
  end
end

group :acceptance do
  if beaker_version
    #use the specified version
    gem 'beaker', *location_for(beaker_version)
  else
    # use the pinned version

    # We're pinning to a a prerelease version of beaker that fixes errors when
    # running with an OS X host. We only need to do this until a new version is
    # released with the fix included.
    gem 'beaker', :git => 'git://github.com/puppetlabs/beaker.git', :ref => '3e2e1e6d3e0234612c3a6af7dbfac06c797676f4'
  end
  # This forces google-api-client to not download retirable 2.0.0 which lacks
  # ruby 1.9.x support.
  gem 'retriable', '~> 1.4'
end
