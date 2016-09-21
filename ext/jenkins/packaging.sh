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

export COW="base-jessie-amd64.cow base-precise-amd64.cow base-trusty-amd64.cow base-wheezy-amd64.cow"
export MOCK="pl-el-6-x86_64 pl-el-7-x86_64"
tmp_m2=$(pwd)/$(mktemp -d m2-local.XXXX)

set -e
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- install
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- deps
# The ci-voom profile has nexus credentials
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- with-profile user,ci-voom deploy
lein update-in : assoc :local-repo "\"${tmp_m2}\"" -- with-profile ezbake ezbake build
set +e

pushd "target/staging"
PACKAGE_BUILD_VERSION=$(rake pl:print_build_param[ref] | tail -n 1)

cat > "${WORKSPACE}/puppetdb.packaging.props" <<PROPS
PACKAGE_BUILD_VERSION=${PACKAGE_BUILD_VERSION}
PROPS
popd

echo "**********************************************"
echo "NOW DO S3 MAGIC"
echo "**********************************************"

REPO_DIR=/opt/jenkins-builds/puppetdb/$PACKAGE_BUILD_VERSION/repos
REPO_CONFIGS=/opt/jenkins-builds/puppetdb/$PACKAGE_BUILD_VERSION/repo_configs
NAME=puppetdb
BUCKET_NAME=${NAME}-prerelease

S3_BRANCH_PATH=s3://${BUCKET_NAME}/${NAME}/${PACKAGE_BUILD_VERSION}

set -x

# Set ship targets and build team for packaging repo
REPO_HOST=builds.delivery.puppetlabs.net
export REPO_DIR REPO_HOST


# Now rebuild the metadata
ssh $REPO_HOST <<PUBLISH_PACKAGES

set -e
set -x

echo "BUCKET_NAME IS: ${BUCKET_NAME}"

time s3cmd --verbose --acl-public --delete-removed  sync ${REPO_CONFIGS}/*  ${S3_BRANCH_PATH}/repo_configs/

time s3cmd --verbose --acl-public --delete-removed  sync ${REPO_DIR}/apt/{jessie,lucid,precise,trusty,wheezy,stable,testing}  ${S3_BRANCH_PATH}/repos/apt/

time s3cmd --verbose --acl-public --delete-removed  sync ${REPO_DIR}/el/{5,6,7}  ${S3_BRANCH_PATH}/repos/el/

time s3cmd --verbose --acl-public --delete-removed  sync ${REPO_DIR}/fedora/f*  ${S3_BRANCH_PATH}/repos/fedora/

PUBLISH_PACKAGES
