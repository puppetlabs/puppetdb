#!/usr/bin/env bash

echo "**********************************************"
echo "PARAMS FROM UPSTREAM:"
echo ""
echo "PUPPETDB_BRANCH: ${PUPPETDB_BRANCH}"
echo "GEM_SOURCE: ${GEM_SOURCE}"
echo "SHIP_NIGHTLY: ${SHIP_NIGHTLY}"
echo "REPO_URL: ${REPO_URL}"
echo "**********************************************"

set -e

DBNAME=$(echo "p-p-e_${PUPPETDB_BRANCH}_${BUILD_ID}_${BUILD_NUMBER}_${PUPPETDB_DBTYPE}_${JDK}" | tr - _)
DBUSER="puppetdb"
DBHOST="fixture-pg94.delivery.puppetlabs.net"
DBPORT="5432"

# Set up project specific database variables
export PUPPETDB_DBUSER="$DBUSER"
export PUPPETDB_DBPASSWORD="puppetdb137"
export PGPASSWORD="puppetdb137"
export PUPPETDB_DBSUBNAME="//${DBHOST}:${DBPORT}/${DBNAME}"

# Set up the second project database variables
DBNAME2=$(echo "p-p-e_${PUPPETDB_BRANCH}_${BUILD_ID}_${BUILD_NUMBER}_${PUPPETDB_DBTYPE}2_${JDK}" | tr - _)
export PUPPETDB2_DBUSER="$DBUSER"
export PUPPETDB2_DBPASSWORD="puppetdb137"
export PUPPETDB2_DBSUBNAME="//${DBHOST}:${DBPORT}/${DBNAME2}"

rm -f testreports.xml *.war *.jar

export HTTP_CLIENT="wget --no-check-certificate -O"

psql -h "${DBHOST}" -U "${DBUSER}" -d postgres -c "create database ${DBNAME}"
psql -h "${DBHOST}" -U "${DBUSER}" -d postgres -c "create database ${DBNAME2}"

lein --version
lein clean
lein deps
lein compile

# Sadly, no JUnit output at this point in time.
lein test

# Clean up our database
psql -h "${DBHOST}" -U "${DBUSER}" -d postgres -c "drop database ${DBNAME}"
psql -h "${DBHOST}" -U "${DBUSER}" -d postgres -c "drop database ${DBNAME2}"
