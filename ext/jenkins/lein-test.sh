#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

export PGHOST=fixture-pg94.delivery.puppetlabs.net
export PGPORT=5432
export HTTP_CLIENT="wget --no-check-certificate -O"

pgdir="$(pwd)/test-resources/var/pg"
readonly pgdir

rm -f testreports.xml *.war *.jar

PGUSER=puppetdb ext/bin/setup-pdb-pg "$pgdir"

lein --version
lein clean

exec ext/bin/pdb-test-env "$pgdir" lein test
