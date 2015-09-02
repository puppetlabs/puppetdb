#!/usr/bin/env bash

echo "**********************************************"
echo "PARAMS FROM UPSTREAM:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "LEIN_PROJECT_VERSION: ${LEIN_PROJECT_VERSION}"
echo "GEM_SOURCE: ${GEM_SOURCE}"
echo "SHIP_NIGHTLY: ${SHIP_NIGHTLY}"
echo "REPO_URL: ${REPO_URL}"
echo "**********************************************"

set -x
set -e

export COW="base-jessie-amd64.cow base-precise-amd64.cow base-trusty-amd64.cow base-wheezy-amd64.cow base-wily-amd64.cow base-xenial-amd64.cow"
export MOCK="pl-el-6-x86_64 pl-el-7-x86_64"
tmp_m2=$(pwd)/$(mktemp -d m2-local.XXXX)

lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- install
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- deps
# The ci-voom profile has nexus credentials
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- with-profile user,ci-voom deploy
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- with-profile ezbake ezbake stage

pushd "target/staging"
rake package:bootstrap
rake pl:jenkins:uber_build[5]

cat > "${WORKSPACE}/puppetdb.packaging.props" <<PROPS
PUPPETDB_PACKAGE_BUILD_VERSION=$(rake pl:print_build_param[ref] | tail -n 1)
PROPS
popd

set -x
