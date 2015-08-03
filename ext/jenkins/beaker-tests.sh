#!/bin/bash -e

echo "**********************************************"
echo "PARAMS FROM UPSTREAM:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "REF: ${REF}"
echo "GEM_SOURCE: ${GEM_SOURCE}"
echo "SHIP_NIGHTLY: ${SHIP_NIGHTLY}"
echo "REPO_URL: ${REPO_URL}"
echo "PACKAGE_BUILD_VERSION: ${PACKAGE_BUILD_VERSION}"
echo "**********************************************"

# Beaker params
export BEAKER_COLOR=false
export BEAKER_XML=true
export PUPPETDB_INSTALL_TYPE=package
export PUPPETDB_USE_PROXIES=false
export BEAKER_project=PuppetDB
export BEAKER_department=eso-dept
export BEAKER_PRESERVE_HOSTS=onfail

# S3 params
export PUPPETDB_PACKAGE_REPO_HOST="puppetdb-prerelease.s3.amazonaws.com"
export PUPPETDB_PACKAGE_REPO_URL="http://puppetdb-prerelease.s3.amazonaws.com/puppetdb/${PACKAGE_BUILD_VERSION}"
export PUPPETDB_PACKAGE_BUILD_VERSION=$PACKAGE_BUILD_VERSION

# Setup RVM and gemset
[[ -s "/usr/local/rvm/scripts/rvm" ]] && source /usr/local/rvm/scripts/rvm
rvm use ruby-2.0.0-p481

# Remove old vendor directory to ensure we have a clean slate
if [ -d "vendor" ];
then
  rm -rf vendor
fi
mkdir vendor

bundle install --path=vendor/bundle --without=test --retry=10

# Now run our tests
bundle exec rake beaker:acceptance
