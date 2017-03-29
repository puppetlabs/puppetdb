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

set -x

# Beaker params
export BEAKER_COLOR=false
export BEAKER_XML=true
export PUPPETDB_INSTALL_TYPE=package
export PUPPETDB_USE_PROXIES=false
export BEAKER_project=PuppetDB
export BEAKER_department=sre-dept
export BEAKER_PRESERVE_HOSTS=onfail
export BEAKER_COLLECT_PERF_DATA=aggressive
export BEAKER_OPTIONS=acceptance/options/${PUPPETDB_DATABASE}.rb
export BEAKER_CONFIG=acceptance/hosts.cfg
bundle exec beaker-hostgenerator $LAYOUT > $BEAKER_CONFIG

bundle exec rake beaker:acceptance
