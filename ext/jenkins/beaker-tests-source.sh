#!/usr/bin/env bash

echo "**********************************************"
echo "PARAMS:"
echo ""
echo "sha1: ${sha1}"
echo "GEM_SOURCE: ${GEM_SOURCE}"
echo "REPO_URL: ${REPO_URL}"
echo "**********************************************"

# Setup RVM and gemset
[[ -s "/usr/local/rvm/scripts/rvm" ]] && source /usr/local/rvm/scripts/rvm
rvm use ruby-1.9.3-p484

set -e # Fail if we receive non-zero exit code
set -x # Start tracing
set -u # Fail on uninitialized variables

# beaker params
export BEAKER_COLOR=false
export BEAKER_XML=true
export PUPPETDB_USE_PROXIES=false
export BEAKER_project=PuppetDB
export BEAKER_department=Engineering

# Remove old vendor directory to ensure we have a clean slate
if [ -d "vendor" ];
then
  rm -rf vendor
fi
mkdir vendor

bundle install --path vendor/bundle --without test

# Now run our tests
PUPPETDB_REPO_PUPPETDB="${REPO_URL}#${sha1}" \
bundle exec rake test:beaker
