#!/bin/bash

set -ueo pipefail
set -x

ulimit -u 4096

case "$PDB_TEST_LANG" in
  clojure)
    java -version
    case "$PDB_TEST_DB" in
      postgres)
        psql -c 'create database puppetdb_test;' -U postgres
        PUPPETDB_DBTYPE=postgres \
          PUPPETDB_DBUSER=postgres \
          PUPPETDB_DBSUBNAME=//127.0.0.1:5432/puppetdb_test \
          PUPPETDB_DBPASSWORD= \
            lein2 test
        ;;
      hsqldb)
        PUPPETDB_DBTYPE=hsqldb lein2 test
        ;;
      *)
        echo "Invalid database: $PDB_TEST_DB" 1>&2
        exit 1
        ;;
    esac
    ;;
  ruby)
    rvm use 1.9.3
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
