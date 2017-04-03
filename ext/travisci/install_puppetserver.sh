#!/usr/bin/env bash

set -e
set -o pipefail

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

git clone https://github.com/puppetlabs/puppetserver

cd puppetserver
git checkout "$PUPPETSERVER_VERSION"
lein install
MAVEN_VER="$(lein-pprint :version)"
echo "$MAVEN_VER"
cd ..

update_dependency_var project.clj puppetserver-version "$MAVEN_VER"
