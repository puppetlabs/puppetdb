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
echo "**********************************************"

set -x
set -e

# Upload release versions to nexus
version="$(grep 'def pdb-version' project.clj | cut -d\" -f 2)"
test "$version"

if ! [[ "$version" =~ SNAPSHOT ]]; then
    echo "${version} appears to be a release version"
    nexus_base="http://nexus.delivery.puppetlabs.net/service/local/repositories"
    probe_url="${nexus_base}/releases/content/puppetlabs/puppetdb/${version}/puppetdb-${version}.jar"
    status="$(curl --head --silent -o /dev/null -w "%{http_code}" "${probe_url}")"
    if [ "$status" -eq 200 ]; then
        echo "puppetdb-${version} already exists on Nexus; skipping deploy"
    else
        echo "Deploying puppetdb ${version} to releases"
        lein deploy releases
    fi
else
    echo "${version} appears to be a snapshot version"

    # There is a bug in ezbake (EZ-35) that requires a circular dependency (in
    # the ezbake profile) for bootstrap.cfg to be properly included. When we run
    # ezbake, lein tries to resolve this dependency; so if we're doing a
    # snapshot build, we need to put something in nexus for it to find.
    lein deploy snapshots
fi

# Build packages using a locally created jar
export COW="base-jessie-amd64.cow base-precise-amd64.cow base-trusty-amd64.cow base-wheezy-amd64.cow base-wily-amd64.cow base-xenial-amd64.cow"
export MOCK="pl-el-6-x86_64 pl-el-7-x86_64"

lein with-profile ezbake ezbake build

pushd "target/staging"
cat > "${WORKSPACE}/puppetdb.packaging.props" <<PROPS
PUPPETDB_PACKAGE_BUILD_VERSION=$(rake pl:print_build_param[ref] | tail -n 1)
PROPS
popd
