#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

pushd ..
git clone https://github.com/puppetlabs/pe-puppetdb-extensions.git
popd

run-unit-tests()
(
  pgdir="$(pwd)/test-resources/var/pg"
  readonly pgdir

  export PGHOST=127.0.0.1
  export PGPORT=5432
  ext/bin/setup-pdb-pg "$pgdir"
  ext/bin/pdb-test-env "$pgdir" lein2 test
  cd ..
  cd pe-puppetdb-extensions
  ext/bin/pdb-test-env "$pgdir" lein2 test
)

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
