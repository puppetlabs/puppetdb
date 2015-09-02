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
export BEAKER_department=eso-dept
export BEAKER_PRESERVE_HOSTS=onfail
export BEAKER_COLLECT_PERF_DATA=aggressive

# Transform LAYOUT from old static host config style to genconfig2 style
# Once the necessary PRs (puppetlabs/puppetdb#1636 and
# puppetlabs/ci-job-configs#529) for both this repo and ci-job-configs have been
# merged, this logic can be removed.
[ "$LAYOUT" = "ec2-west-el7-64mda-el7-64a" ] \
    && LAYOUT=centos7-64mda-64a
[ "$LAYOUT" = "ec2-west-el6-64mda-el6-64a" ] \
    && LAYOUT=centos6-64mda-64a
[ "$LAYOUT" = "ec2-west-ubuntu1204-64mda-64a" ] \
    && LAYOUT=ubuntu1204-64mda-64a
[ "$LAYOUT" = "ec2-west-debian7-64mda-64a" ] \
    && LAYOUT=debian7-64mda-64a
[ "$LAYOUT" = "ec2-west-el6-64mda-el5-64a-ubuntu1204-64a" ] \
    && LAYOUT=centos6-64mda-centos5-64a-ubuntu1204-64a
[ "$LAYOUT" = "ec2-west-debian7-64mda-fallback" ] \
    && LAYOUT=debian7-64mda-64d

export BEAKER_OPTIONS=acceptance/options/${PUPPETDB_DATABASE}.rb
export BEAKER_CONFIG=acceptance/hosts.cfg
bundle exec genconfig2 $LAYOUT > $BEAKER_CONFIG

bundle exec rake beaker:acceptance
