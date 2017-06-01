#!/usr/bin/env bash

echo "**********************************************"
echo "PARAMS FROM UPSTREAM:"
echo ""
echo "WORKSPACE: ${WORKSPACE}"
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "LEIN_PROJECT_VERSION: ${LEIN_PROJECT_VERSION}"
echo "GEM_SOURCE: ${GEM_SOURCE}"
echo "SHIP_NIGHTLY: ${SHIP_NIGHTLY}"
echo "REPO_URL: ${REPO_URL}"
echo "PE_VER: ${PE_VER}"
echo "**********************************************"

set -x
set -e

PE_VER="$1"
test "$PE_VER"

# Upload release versions to nexus
version="$(grep 'def pdb-version' project.clj | cut -d\" -f 2)"
test "$version"

if ! [[ "$version" =~ SNAPSHOT ]]; then
    echo "${version} appears to be a release version"
    nexus_base="http://nexus.delivery.puppetlabs.net/service/local/repositories"
    probe_url="${nexus_base}/releases/content/puppetlabs/pe-puppetdb-extensions/${version}/pe-puppetdb-extensions-${version}.jar"
    status="$(curl --head --silent -o /dev/null -w "%{http_code}" "${probe_url}")"
    if [ "$status" -eq 200 ]; then
        echo "pe-puppetdb-extensions-${version} already exists on Nexus; skipping deploy"
    else
        echo "Deploying pe-puppetdb-extensions ${version} to releases"
        lein deploy releases
    fi
else
    echo "${version} appears to be a snapshot version"
fi

# Build packages using a locally created jar
PE_VER="$PE_VER" lein with-profile ezbake ezbake build

pushd "target/staging"
cat > "${WORKSPACE}/pe-puppetdb-extensions.packaging.props" <<PROPS
PUPPETDB_PACKAGE_BUILD_VERSION=$(rake pl:print_build_param[ref] | tail -n 1)
PROPS
popd
