#!/bin/bash

set -ueo pipefail
set -x

ulimit -u 4096

case "$PDB_TEST_LANG" in
  clojure)
    java -version
    psql -c 'create database puppetdb_test;' -U postgres
    psql -c 'create database puppetdb2_test;' -U postgres
    PUPPETDB_DBUSER=postgres \
      PUPPETDB_DBSUBNAME=//127.0.0.1:5432/puppetdb_test \
      PUPPETDB_DBPASSWORD= \
      PUPPETDB2_DBUSER=postgres \
      PUPPETDB2_DBSUBNAME=//127.0.0.1:5432/puppetdb2_test \
      PUPPETDB2_DBPASSWORD= \
        lein2 test
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
