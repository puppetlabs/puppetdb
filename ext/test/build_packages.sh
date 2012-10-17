set -x
DIR="$( dirname "$0" )"
if [ "$PUPPETDB_PACKAGE_TYPE" = "rpm" ]; then
    sh $DIR/build_packages_rpm.sh
else
    sh $DIR/build_packages_deb.sh
fi