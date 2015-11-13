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
export PDB_TEST_DB_USER=puppetdb
export PDB_TEST_DB_ADMIN=puppetdb
export PDB_TEST_DB_USER_PASSWORD=puppetdb137
export PDB_TEST_DB_ADMIN_PASSWORD=puppetdb137

PDB_TEST_ID="$(ruby -e "require 'securerandom';  puts SecureRandom.uuid")"
PDB_TEST_ID="$(echo "$PDB_TEST_ID" | perl -pe 's/[^a-zA-Z0-9_]/_/gmo')"
declare -rx PDB_TEST_ID

lein --version
lein clean
exec lein test
