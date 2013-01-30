set -e

NAME=puppetdb

echo "**********************************************"
echo "RUNNING CLOUD DEB PACKAGING; PARAMS FROM UPSTREAM BUILD:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "**********************************************"

# `git describe` returns something like 1.0.0-20-ga1b2c3d
# We want 1.0.0.20, so replace - with . and get rid of the g bit
# TODO: pass along version?
VERSION=`git describe | sed -e 's/-/./g' -e 's/\.*g.*//'`
REF_TYPE=$(git cat-file -t $(git describe))

DEB_BUILD_BRANCH=${PUPPETDB_BRANCH}
DEB_BUILD_DIR="~/$NAME/build/$DEB_BUILD_BRANCH"
FREIGHT_DIR=/opt/dev/$NAME/$DEB_BUILD_BRANCH
INCOMING=$FREIGHT_DIR/incoming
PENDING=$FREIGHT_DIR/pending
WORK_DIR=$DEB_BUILD_DIR/$NAME-$VERSION
BUCKET_NAME=${NAME}-prerelease
S3_BRANCH_PATH=s3://${BUCKET_NAME}/${NAME}/${DEB_BUILD_BRANCH}
[ -n "${PE_VER}" ] || PE_VER='2.7'

git archive --format=tar HEAD --prefix=$NAME-$VERSION/ -o $NAME-$VERSION.tar

ssh deb-builder "mkdir -p $DEB_BUILD_DIR"

scp $NAME-$VERSION.tar deb-builder:$DEB_BUILD_DIR

rm -f $NAME-$VERSION.tar

ssh neptune.puppetlabs.lan "mkdir -p ${INCOMING}"

ssh deb-builder <<BUILD_DEBS
set -e
set -x

export PATH=~/bin:\$PATH

#tar -C ~/$NAME/build/$BUILD_BRANCH -xvf ~/$NAME/build/$BUILD_BRANCH/$NAME-$VERSION.tar
tar -C $DEB_BUILD_DIR -xvf $DEB_BUILD_DIR/$NAME-$VERSION.tar
echo $VERSION > $WORK_DIR/version

# This rake call builds our FOSS debs
cd $WORK_DIR && rake deb

set -x

echo "ABOUT TO COPY OVER THE DEB"
scp -r $WORK_DIR/pkg/deb neptune.puppetlabs.lan:${INCOMING}/$NAME-$VERSION

# This one is going to build us our PE debs
# To avoid a tremendous amount of output, we'll +x this
set +x
cd $WORK_DIR && rake deb PE_BUILD=true PE_VER=${PE_VER}

set -x

# We ship a second time here because the second rake deb call blows away
# the results of the first. We could also move the first set of artifacts
# aside and only ship once, but, you know, since we're already at 11/10 on
# the hack scale here...
echo "ABOUT TO COPY OVER THE PE DEBS"
scp -r $WORK_DIR/pkg/deb/{lucid,squeeze,precise,wheezy} neptune.puppetlabs.lan:${INCOMING}/$NAME-$VERSION

rm -rf $WORK_DIR{,.tar}
BUILD_DEBS

# That's right, I'm templating a config file via shell script inside
# a Jenkins job.  So?  Back off.
cat <<FREIGHT_CONF > ./freight.conf
# Example Freight configuration.

# Directories for the Freight library and Freight cache.  Your web
# server's document root should be '\$VARCACHE'.
VARLIB="${FREIGHT_DIR}/freight.dev"
VARCACHE="${FREIGHT_DIR}/debian"

# Default 'Origin' and 'Label' fields for 'Release' files.
ORIGIN="Puppetlabs"
LABEL="Puppetlabs"

# Defaults to just i386 and amd64, this means we can deb up scripts.
ARCHS="i386 amd64 all"

# GPG key to use to sign repositories.  This is required by the 'apt'
# repository provider.  Use 'gpg --gen-key' (see 'gpg'(1) for more
# details) to generate a key and put its email address here.
GPG="pluto@puppetlabs.lan"
FREIGHT_CONF

scp ./freight.conf neptune.puppetlabs.lan:${FREIGHT_DIR}

ssh neptune.puppetlabs.lan <<FREIGHT
set -e
set -x

# This adds the FOSS debs, which are at the first directory level
for DISTRO in lucid maverick natty oneiric precise lenny squeeze wheezy; do
  freight add -c ${FREIGHT_DIR}/freight.conf $INCOMING/$NAME-$VERSION/*.deb apt/\$DISTRO
done

# The PE debs are all in subdirectories named after their dist
for DISTRO in lucid squeeze precise wheezy; do
  freight add -c ${FREIGHT_DIR}/freight.conf ${INCOMING}/$NAME-$VERSION/\$DISTRO/*.deb apt/\$DISTRO
done

freight cache -c ${FREIGHT_DIR}/freight.conf

# If this is a tagged version, we want to save the results for later promotion.
if [ "$REF_TYPE" = "tag" ]; then
  mkdir -p $PENDING/$NAME-$VERSION/deb
  cp -R $INCOMING/$NAME-$VERSION/* $PENDING/$NAME-$VERSION/deb
fi

rm -rf $INCOMING/$NAME-$VERSION


set -x

# NOTE: be careful with shell vars in here!  It's kind of a mind-f to keep track of which ones
# you want to interpolate from the jenkins node and which from neptune!

echo "BUCKET_NAME IS: ${BUCKET_NAME}"

# In the /opt/dev/debian/dists dir, all that we really care about syncing are the directories
# that match up with release names.  These happen to all be symlinks, which s3cmd doesn't like,
# and there are a bunch of freight work directories that we don't want/need to sync.  This
# hack finds the symlinks and syncs each one individually.
cd $FREIGHT_DIR/debian
find dists -type l
for x in \`find dists -type l\`; do
   echo "ATTEMPTING TO SYNC DIST: '\${x}'"
   time s3cmd --verbose --acl-public --delete-removed  sync $FREIGHT_DIR/debian/\${x}/* ${S3_BRANCH_PATH}/debian/\${x}/
done
cd -

# Now we sync the rest of the stuff besides the 'dists' dir
time s3cmd --verbose --acl-public --delete-removed  sync ${FREIGHT_DIR}/debian/pool/* ${S3_BRANCH_PATH}/debian/pool/
time s3cmd --verbose --acl-public sync ${FREIGHT_DIR}/debian/*.gpg ${S3_BRANCH_PATH}/debian/

FREIGHT
