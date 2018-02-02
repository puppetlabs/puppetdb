#!/usr/bin/env bash

set -exo pipefail

export PUPPETSERVER_HEAP_SIZE=1G

# Update a single dependency in a leiningen project.clj file.
update_dependency_var() {
    local file="$1"
    local varname="$2"
    local new_version="$3"

    SED_ADDRESS="(def $varname"
    SED_REGEX="\".*\""
    SED_REPLACEMENT="\"$new_version\""
    SED_COMMAND="s|$SED_REGEX|$SED_REPLACEMENT|"

    set -x
    sed -i -e "/$SED_ADDRESS/ $SED_COMMAND" "$file"
}

lein-pprint() {
    lein with-profile dev,ci pprint "$@" | sed -e 's/^"//' -e 's/"$//'
}

if test -n "$PUPPETSERVER_VERSION"
then
    PUPPETSERVER_VERSION="$(lein-pprint :pdb-puppetserver-test-version)"
fi

git clone https://github.com/puppetlabs/puppetserver

cd puppetserver
git checkout "$PUPPETSERVER_VERSION"
lein install
maven_ver="$(lein-pprint :version)"
echo "$maven_ver"
test -n "$maven_ver"
cd ..

update_dependency_var project.clj puppetserver-version "$maven_ver"
