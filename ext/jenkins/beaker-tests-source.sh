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
rvm use ruby-2.0.0-p481

set -e # Fail if we receive non-zero exit code
set -x # Start tracing
set -u # Fail on uninitialized variables

# beaker params
export BEAKER_COLOR=false
export BEAKER_XML=true
export PUPPETDB_USE_PROXIES=false
export BEAKER_project=PuppetDB
export BEAKER_department=eso-dept

# Remove old vendor directory to ensure we have a clean slate
if [ -d "vendor" ];
then
  rm -rf vendor
fi
mkdir vendor

bundle install --path=vendor/bundle --without=test --retry=10

# Install a copy of leiningen
if [ -d "leiningen" ];
then
  rm -rf leiningen
fi
mkdir leiningen

wget 'https://raw.githubusercontent.com/technomancy/leiningen/2.5.1/bin/lein' -O leiningen/lein
chmod +x leiningen/lein

# Now run our tests
PATH=$PATH:$(pwd)/leiningen \
bundle exec rake beaker:acceptance
