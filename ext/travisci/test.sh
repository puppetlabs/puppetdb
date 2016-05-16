#!/bin/bash

set -ueo pipefail
set -x

ulimit -u 4096

LEIN="${1:-lein2}"
top="$(pwd)"

# get_dep_version
# :arg1 is the dependency project whose version we want to grab from project.clj
get_dep_version() {
    echo "$("$LEIN" with-profile dev,ci pprint :"${1:?}" | cut -d\" -f2)"
}

# checkout_repo
# :arg1 is the name of the repo to check out
# :arg2 is the artifact id of the project
checkout_repo() {
    local repo="$1"
    local depname="$2"

    git clone "git@github.com:puppetlabs/${repo}.git"

    # We need to make sure we are using the correct "release" tag.  It seems
    # unlikely in travisci context, but in the future we may need to check
    # whether this is a SNAPSHOT version and behave differently if so.
    local depversion
    depversion="$(get_dep_version "${depname}")"

    if [ -z "$depversion" ]; then
        echo "Could not get a dependency version for: ${depname}, exiting..."
        exit 1
    fi

    pushd "${repo}"
    git checkout "${depversion}"
    if [ "${repo}" == pe-rbac-service ]; then
        sed -i -e 's/\[puppetlabs\/pe-activity-service/#_\[puppetlabs\/pe-activity-service/g' project.clj
        sed -i -e 's/\[puppetlabs\/rbac-client/#_\[puppetlabs\/rbac-client/g' project.clj
    fi
    if [ "${repo}" == pe-activity-service ]; then
        sed -i -e 's/\[puppetlabs\/pe-rbac-service/#_\[puppetlabs\/pe-rbac-service/g' project.clj
    fi
    lein install
    popd
}

rm -rf checkouts
mkdir checkouts

# Clone each dependency locally into the ./checkouts directory so we can install
# from source into the local ~/.m2/repository
cd checkouts

checkout_repo clj-schema-tools schema-tools
checkout_repo liberator-util liberator-util
checkout_repo clj-rbac-client rbac-client
checkout_repo pe-rbac-service pe-rbac-service
checkout_repo pe-activity-service pe-activity-service

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
