#!/bin/bash

T_LANG="${T_TEST%%:*}"
T_VERSION="${T_TEST#*:}"

echo "Running tests in language: ${T_LANG} version: ${T_VERSION}"

if [ $T_LANG == "ruby" ]; then
  # Using `rvm use` in travis-ci needs a lot more work, so just accepting
  # the default version for now.
  ruby -v
  bundle install
  cd puppet
  bundle exec rspec spec/
else
  if [ $T_LANG == "java" ]; then
    jdk_switcher use $T_VERSION
    java -version
    lein2 test
  else
    echo "Invalid language ${T_LANG}"
    exit 1
  fi
fi
