#!/bin/bash -e

[[ -s "$HOME/.rvm/scripts/rvm" ]] && source "$HOME/.rvm/scripts/rvm"
rvm use $ruby$gemset

# Remove old vendor directory to ensure we have a clean slate
if [ -d "vendor" ];
then
  rm -rf vendor
fi
mkdir vendor

# Lets install the gems in bundle
if [ "$ruby" != "ruby-1.8.5" ];
then
  bundle install --path vendor/bundle --without acceptance
fi

echo "**********************************************"
echo "RUNNING SPECS; PARAMS FROM UPSTREAM BUILD:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "**********************************************"

(
  cd vendor
  git clone --depth 1 git://github.com/puppetlabs/facter.git

  # Checkout Puppet branch
  git clone --depth 1 --branch ${puppet_branch} git://github.com/puppetlabs/puppet.git

  git clone --depth 1 git://github.com/puppetlabs/puppetlabs_spec_helper.git
)

export RUBYLIB=$RUBYLIB:`pwd`/vendor/facter/lib/:`pwd`/vendor/puppet/lib/:`pwd`/vendor/puppetlabs_spec_helper/lib

cat >/tmp/force_gc.rb <<RUBY
def GC.disable; end
class RSpec::Core::Configuration
  def exclusion_filter=(filter)
    settings[:exclusion_filter].merge!(filter)
  end
end
RUBY

cd puppet
bundle exec rspec spec -r /tmp/force_gc.rb -fd --tag "~@fails_on_${ruby/-/_}"
