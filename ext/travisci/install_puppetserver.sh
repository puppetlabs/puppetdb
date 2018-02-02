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

# If PUPPETSERVER_VERSION has not been specified, then use the version
# already specified in project.clj, otherwise use PUPPETSERVER_VERSION
# and adjust the project.clj to match.

server_version="$PUPPETSERVER_VERSION"
if test -z "$server_version"
then
    server_version="$(lein-pprint :pdb-puppetserver-test-version)"
fi
test "$server_version"

git clone -b "$server_version" https://github.com/puppetlabs/puppetserver
cd puppetserver
lein install
cd ..

if test "$PUPPETSERVER_VERSION"  # i.e. we're not using the default version
then
    update_dependency_var project.clj puppetserver-version "$server_version"
fi
