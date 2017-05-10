#!/bin/bash -e

echo "**********************************************"
echo "PARAMS FROM UPSTREAM:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "GEM_SOURCE: ${GEM_SOURCE}"
echo "SHIP_NIGHTLY: ${SHIP_NIGHTLY}"
echo "REPO_URL: ${REPO_URL}"
echo "**********************************************"

set -x

[[ -s "/usr/local/rvm/scripts/rvm" ]] && source "/usr/local/rvm/scripts/rvm"
rvm use $ruby

# Remove old vendor directory to ensure we have a clean slate
if [ -d "vendor" ];
then
  rm -rf vendor
fi
mkdir vendor

export GEM_SOURCE="http://rubygems.delivery.puppetlabs.net"

NO_ACCEPTANCE=true bundle install --path vendor/bundle --without acceptance

cd puppet
NO_ACCEPTANCE=true bundle exec rspec spec -fd
