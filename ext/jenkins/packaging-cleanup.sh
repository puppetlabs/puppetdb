#!/bin/bash

echo "**********************************************"
echo "PARAMS FROM UPSTREAM:"
echo ""
echo "PACKAGE_BUILD_VERSION: ${PACKAGE_BUILD_VERSION}
echo" "**********************************************"

REPO_DIR=/opt/jenkins-builds/puppetdb/$PACKAGE_BUILD_VERSION/repos
REPO_CONFIGS=/opt/jenkins-builds/puppetdb/$REF/repo_configs
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

time s3cmd del --recursive  ${S3_BRANCH_PATH}/

PUBLISH_PACKAGES
