#!/bin/bash

T_TEST_ARRAY=(${T_TEST//:/ })

T_LANG=${T_TEST_ARRAY[0]}
T_VERSION=${T_TEST_ARRAY[1]}
T_DB=${T_TEST_ARRAY[2]}

echo "Running tests in language: ${T_LANG} version: ${T_VERSION}"

if [ $T_LANG == "ruby" ]; then
  # Using `rvm use` in travis-ci needs a lot more work, so just accepting
  # the default version for now.
  ruby -v
  gem install bundler
  bundle install --without acceptance
  cd puppet
  bundle exec rspec spec/
else
  if [ $T_LANG == "java" ]; then
    ulimit -u 4096
    jdk_switcher use $T_VERSION
    java -version
    if [ $T_DB == "postgres" ]; then
      psql -c 'create database puppetdb_test;' -U postgres
      PUPPETDB_DBTYPE=$T_DB \
      PUPPETDB_DBUSER=postgres \
      PUPPETDB_DBSUBNAME=//127.0.0.1:5432/puppetdb_test \
      PUPPETDB_DBPASSWORD= \
      lein2 test
    else
      if [ $T_DB == "hsqldb" ]; then
        PUPPETDB_DBTYPE=$T_DB \
        lein2 test
      else
        echo "Invalid database ${T_DB}"
        exit 1
      fi
    fi
  else
    echo "Invalid language ${T_LANG}"
    exit 1
  fi
fi
