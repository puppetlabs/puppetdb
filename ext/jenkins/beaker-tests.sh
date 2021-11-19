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

# Once the necessary PR (puppetlabs/ci-job-configs#2319) has been
# merged, this logic can be removed.
[ "$LAYOUT" = "ec2-west-el7-64mda-el7-64a" ] \
    && LAYOUT=centos7-64mda-64a
[ "$LAYOUT" = "ec2-west-el6-64mda-el6-64a" ] \
    && LAYOUT=centos6-64mda-64a
[ "$LAYOUT" = "ec2-west-ubuntu1204-64mda-64a" ] \
    && LAYOUT=ubuntu1204-64mda-64a
[ "$LAYOUT" = "ec2-west-ubuntu1404-64mda-64a" ] \
    && LAYOUT=ubuntu1404-64mda-64a
[ "$LAYOUT" = "ec2-west-ubuntu1604-64mda-64a" ] \
    && LAYOUT=ubuntu1604-64mda-64a
[ "$LAYOUT" = "ec2-west-debian7-64mda-64a" ] \
    && LAYOUT=debian7-64mda-64a
[ "$LAYOUT" = "ec2-west-el6-64mda-el5-64a-ubuntu1204-64a" ] \
    && LAYOUT=centos6-64mda-centos5-64a-ubuntu1204-64a
[ "$LAYOUT" = "ec2-west-debian7-64mda-fallback" ] \
    && LAYOUT=debian7-64mda-64d
[ "$LAYOUT" = "ec2-west-debian8-64mda-64a" ] \
    && LAYOUT=debian8-64mda-64a
HYPERVISOR="${HYPERVISOR:-vmpooler}"

export BEAKER_OPTIONS=acceptance/options/${PUPPETDB_DATABASE}.rb
export BEAKER_CONFIG=acceptance/hosts.cfg
bundle exec beaker-hostgenerator $LAYOUT --hypervisor $HYPERVISOR > $BEAKER_CONFIG

bundle exec rake beaker:acceptance
