set -e
set -v

echo "**********************************************"
echo "RUNNING RPM PACKAGING; PARAMS FROM UPSTREAM BUILD:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "**********************************************"
env
echo "**********************************************"

NAME=puppetdb

# `git describe` returns something like 1.0.0-20-ga1b2c3d
# We want 1.0.0.20, so replace - with . and get rid of the g bit
VERSION=`rake version`
REF_TYPE=$(git cat-file -t $(git describe))

RPM_BUILD_BRANCH=${PUPPETDB_BRANCH}
RPM_BUILD_DIR="~/$NAME/build/$RPM_BUILD_BRANCH"
YUM_DIR=/opt/dev/$NAME/$RPM_BUILD_BRANCH
PENDING=$YUM_DIR/pending
BUCKET_NAME=$NAME-prerelease
S3_BRANCH_PATH=s3://${BUCKET_NAME}/${NAME}/${RPM_BUILD_BRANCH}

set -x

# Set ship targets and build team for packaging repo
YUM_HOST=neptune.puppetlabs.lan
YUM_REPO=$YUM_DIR
TEAM=dev
export YUM_HOST YUM_REPO TEAM

rake package:implode --trace
rake package:bootstrap --trace
rake pl:fetch --trace
rake pl:remote:mock_all --trace
rake pl:ship_rpms --trace

# Establish PE building environment variables
PE_BUILD=true
TEAM=pe-dev
export PE_BUILD TEAM

rake pl:fetch --trace
rake pe:mock_all --trace
rake pe:ship_rpms --trace

# If this is a tagged version, we want to save the results for later promotion.
if [ "$REF_TYPE" = "tag" ]; then
  ssh neptune.puppetlabs.lan "mkdir -p $PENDING/$NAME-$VERSION"
  scp -r pkg/el pkg/fedora neptune.puppetlabs.lan:$PENDING/$NAME-$VERSION
fi

# Now rebuild the metadata
ssh $YUM_HOST <<PUBLISH_RPMS

find $YUM_DIR -name x86_64 -or -name i386 -or -name SRPMS | xargs -n 1 createrepo --update

set -e
set -x

echo "BUCKET_NAME IS: ${BUCKET_NAME}"

time s3cmd --verbose --acl-public --delete-removed  sync ${YUM_DIR}/el/* ${S3_BRANCH_PATH}/el/

PUBLISH_RPMS
