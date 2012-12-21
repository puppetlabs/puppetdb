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

# The Rake task depends on having a version file, since it's not a git repo
echo $VERSION > version

# This SRPM is built for ruby 1.8 pathing, so in the newbuild call we turn off fedora 17 building
PATH=~/bin:\$PATH rake srpm

# This file has to exist or rsync freaks out :(
touch excludes

# Clone yum.puppetlabs.com repo for its rake tasks
git clone git@github.com:puppetlabs/yum.puppetlabs.com

# Build the SRPM
RAKE_ARGS="-Iyum.puppetlabs.com -f yum.puppetlabs.com/Rakefile"

# Build the packages and ship them off to neptune
rake \$RAKE_ARGS setup newbuild ship PKG=\$(ls pkg/rpm/$NAME-*.src.rpm) TARGET=neptune.puppetlabs.lan:$YUM_DIR NO_CHECK=true OVERRIDE=1 F17_BUILD=FALSE

# Now we remove and rebuild the SRPM for ruby 1.9 and mock against fedora 17
rm pkg/rpm/$NAME-*.src.rpm
PATH=~/bin:\$PATH rake srpm RUBY_VER=1.9

rake \$RAKE_ARGS setup newbuild ship PKG=\$(ls pkg/rpm/$NAME-*.src.rpm) TARGET=neptune.puppetlabs.lan:$YUM_DIR NO_CHECK=true OVERRIDE=1 MOCKS=fedora-17-i386

# This is a Puppet Enterprise rpm
build_srpm "PE_BUILD=true"
build_rpm "PKG=\$(ls pkg/rpm/pe-$NAME-*.src.rpm)"

# Ship the PE rpms
ship_rpms PE_BUILD=true

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
