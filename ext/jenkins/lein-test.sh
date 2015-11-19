#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

pghost=fixture-pg94.delivery.puppetlabs.net
pgport=5432

export HTTP_CLIENT="wget --no-check-certificate -O"

rm -f testreports.xml *.war *.jar

export PDB_TEST_DB_HOST="$pghost"
export PDB_TEST_DB_PORT="$pgport"

# For now, just conflate the normal test and admin users.
export PDB_TEST_DB_USER=pdb_test
export PDB_TEST_DB_ADMIN=pdb_test_admin
export PDB_TEST_DB_USER_PASSWORD=optuwaeg6ujzo
export PDB_TEST_DB_ADMIN_PASSWORD=daroniwu54ot

PDB_TEST_ID="$(ruby -e "require 'securerandom';  print SecureRandom.uuid")"
PDB_TEST_ID="$(echo -n "$PDB_TEST_ID" | perl -pe 's/[^a-zA-Z0-9_]/_/gmo')"
declare -rx PDB_TEST_ID

lein --version
lein clean
exec lein test
