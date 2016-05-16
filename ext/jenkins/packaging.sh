#!/usr/bin/env bash

PE_VER=${1:-2015.2}

echo "**********************************************"
echo "PARAMS FROM UPSTREAM:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "LEIN_PROJECT_VERSION: ${LEIN_PROJECT_VERSION}"
echo "GEM_SOURCE: ${GEM_SOURCE}"
echo "SHIP_NIGHTLY: ${SHIP_NIGHTLY}"
echo "REPO_URL: ${REPO_URL}"
echo "PE_VER: ${PE_VER}"
echo "**********************************************"

set -x
set -e

tmp_m2=$(pwd)/$(mktemp -d m2-local.XXXX)

lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- install
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- deps
# the ci-voom profile gives us nexus credentials
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- with-profile user,ci-voom deploy
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- with-profile ezbake ezbake stage

pushd "target/staging"
rake package:bootstrap
rake pl:jenkins:uber_build[5] PE_VER=${PE_VER}

cat > "${WORKSPACE}/pe-puppetdb-extensions.packaging.props" <<PROPS
PUPPETDB_PACKAGE_BUILD_VERSION=$(rake pl:print_build_param[ref] | tail -n 1)
PROPS
popd
