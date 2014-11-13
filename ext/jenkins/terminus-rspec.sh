#!/bin/bash -e

[[ -s "$HOME/.rvm/scripts/rvm" ]] && source "$HOME/.rvm/scripts/rvm"
rvm use $ruby$gemset

set -x

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
  BUNDLE_PREFIX="bundle exec"
fi

echo "**********************************************"
echo "RUNNING SPECS; PARAMS FROM UPSTREAM BUILD:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "**********************************************"

export RUBYLIB=$RUBYLIB:`pwd`/puppet/lib

cat >/tmp/force_gc.rb <<RUBY
def GC.disable; end
class RSpec::Core::Configuration
  def exclusion_filter=(filter)
    settings[:exclusion_filter].merge!(filter)
  end
end
RUBY

cd puppet
$BUNDLE_PREFIX rspec spec -r /tmp/force_gc.rb -fd --tag "~@fails_on_${ruby/-/_}"
