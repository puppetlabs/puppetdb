#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

cat /etc/hosts
hostname --fqdn

run-unit-tests()
(
  pgdir="$(pwd)/test-resources/var/pg"
  readonly pgdir

  export PGHOST=127.0.0.1
  export PGPORT=5432
  ext/bin/setup-pdb-pg "$pgdir"
  case "$PDB_TEST_SELECTOR" in
      :integration)
          ext/bin/pdb-test-env "$pgdir" lein test :integration
          ;;
      *)
          ext/bin/pdb-test-env "$pgdir" lein test "$PDB_TEST_SELECTOR"
          ;;
  esac
)

case "$PDB_TEST_LANG" in
  clojure)
    java -version
    run-unit-tests
    ;;
  ruby)
    ruby -v
    cd puppet
    bundle exec rspec spec
    ;;
  *)
    echo "Invalid language: $PDB_TEST_LANG" 1>&2
    exit 1
    ;;
esac
