#!/bin/bash

set -ueo pipefail
set -x

ulimit -u 4096

LEIN="${1:-lein2}"
top="$(pwd)"

# get_dep_version
# :arg1 is the dependency project whose version we want to grab from project.clj
get_dep_version() {
  local REGEX="puppetlabs\/puppetdb\s*\"([[:alnum:]]|\-|\.)+\"[[:space:]]*\]"
  echo "$("$LEIN" with-profile ci pprint :dependencies | egrep "${REGEX}" | cut -d\" -f2)"
}

rm -rf checkouts
mkdir checkouts

# Clone each dependency locally into the ./checkouts directory so we can install
# from source into the local ~/.m2/repository
cd checkouts

git clone https://github.com/puppetlabs/puppetdb

# Try to checkout to the "release" tag in puppetdb corresponding to
# the dependency version. If we can't find it, default to a branch of
# the same name as the current branch
depversion="$(get_dep_version 'puppetdb')"
cd puppetdb

# Warning: the inner quotes here are valid, and shouldn't require escaping
tag="$(git tag -l "${depversion?}")"
if test -n "${tag?}"
then
    git checkout "$depversion"
else
    git checkout "$TRAVIS_BRANCH"
fi
"$LEIN" install

cd "$top"

pgdir="$(pwd)/test-resources/var/pg"
readonly pgdir

export PGHOST=127.0.0.1
export PGPORT=5432

checkouts/puppetdb/ext/bin/setup-pdb-pg "$pgdir"

pdb-env()
{
  "$top/checkouts/puppetdb/ext/bin/pdb-test-env" "$pgdir" "$@"
}

java -version
pdb-env "$LEIN" test
