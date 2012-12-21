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
VERSION=`git describe | sed -e 's/-/./g' -e 's/\.*g.*//'`
REF_TYPE=$(git cat-file -t $(git describe))

RPM_BUILD_BRANCH=${PUPPETDB_BRANCH}
RPM_BUILD_DIR="~/$NAME/build/$RPM_BUILD_BRANCH"
YUM_DIR=/opt/dev/$NAME/$RPM_BUILD_BRANCH
PENDING=$YUM_DIR/pending
WORK_DIR=$RPM_BUILD_DIR/$NAME-$VERSION
BUCKET_NAME=$NAME-prerelease
S3_BRANCH_PATH=s3://${BUCKET_NAME}/${NAME}/${RPM_BUILD_BRANCH}

git archive --format=tar HEAD --prefix=$NAME-$VERSION/ -o $NAME-$VERSION.tar

ssh rpm-builder mkdir -p $RPM_BUILD_DIR
ssh rpm-builder "rm -rf $RPM_BUILD_DIR/*"

scp $NAME-$VERSION.tar rpm-builder:$RPM_BUILD_DIR

rm -f $NAME-$VERSION.tar

ssh rpm-builder <<BUILD_RPMS
set -e
set -x

tar -C $RPM_BUILD_DIR -xvf $RPM_BUILD_DIR/$NAME-$VERSION.tar

cd $WORK_DIR

echo "****************************************************"
env
echo "****************************************************"
ls -l
echo "****************************************************"

function prepare_env(){
# The Rake task depends on having a version file, since it's not a git repo
echo $VERSION > version
RAKE_ARGS="-Iyum.puppetlabs.com -f yum.puppetlabs.com/Rakefile"
PATH=~/bin:\$PATH
# This file has to exist or rsync freaks out :(
touch excludes
# Clone yum.puppetlabs.com repo for its rake tasks
git clone git@github.com:puppetlabs/yum.puppetlabs.com
}

function build_srpm(){
  args=\${1}
  rake srpm \$args
}

function build_rpm(){
  args=\${@}
  rake \$RAKE_ARGS setup newbuild \$args
}

function ship_rpms(){
  args=\${@}
  rake \$RAKE_ARGS ship TARGET=neptune.puppetlabs.lan:$YUM_DIR NO_CHECK=true OVERRIDE=1 \$args
}

prepare_env

# This RPM is built for ruby 1.8 pathing, so in the newbuild call we turn off fedora 17 building
build_srpm
build_rpm "PKG=\$(ls pkg/rpm/$NAME-*.src.rpm)" "F17_BUILD=FALSE"
rm pkg/rpm/$NAME-*.src.rpm

# This RPM is build for ruby 1.9, and mocked against fedora 17
build_srpm "RUBY_VER=1.9"
build_rpm "PKG=\$(ls pkg/rpm/$NAME-*.src.rpm)" "MOCKS=fedora-17-i386"
rm pkg/rpm/$NAME-*.src.rpm

# Ship the rpms staged locally in yum.puppetlabs.com
ship_rpms

# If this is a tagged version, we want to save the results for later promotion.
if [ "$REF_TYPE" = "tag" ]; then
  scp -r el fedora neptune.puppetlabs.lan:$PENDING/$NAME-$VERSION
fi

# Clean up after ourselves
cd ~
rm -rf $WORK_DIR{,.tar}

BUILD_RPMS

#
# Now rebuild the metadata
ssh neptune.puppetlabs.lan <<PUBLISH_RPMS

find $YUM_DIR -name x86_64 -or -name i386 -or -name SRPMS | xargs -n 1 createrepo --update

set -e
set -x

echo "BUCKET_NAME IS: ${BUCKET_NAME}"

time s3cmd --verbose --acl-public --delete-removed  sync ${YUM_DIR}/el/* ${S3_BRANCH_PATH}/el/

PUBLISH_RPMS
