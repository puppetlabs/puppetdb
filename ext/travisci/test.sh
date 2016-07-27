#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

run-unit-tests()
(
  pgdir="$(pwd)/test-resources/var/pg"
  readonly pgdir

  export PGHOST=127.0.0.1
  export PGPORT=5432
  ext/bin/setup-pdb-pg "$pgdir"
  ext/bin/pdb-test-env "$pgdir" lein2 test
)

export PDB_TEST_DUMP_LOG_ON_FAILURE=true

case "$PDB_TEST_LANG" in
  clojure)
    java -version
    run-unit-tests
    ;;
  ruby)
    ruby -v
    gem install bundler
    bundle install --without acceptance
    cd puppet
    bundle exec rspec spec/
    ;;
  *)
    echo "Invalid language: $PDB_TEST_LANG" 1>&2
    exit 1
    ;;
esac
