#!/usr/bin/env bash

lein=lein2

# Update a single dependency in a leiningen project.clj file.
update_dependency_var() {
    local file="$1"
    local varname="$2"
    local new_version="$3"

    SED_ADDRESS="(def $varname"
    SED_REGEX="\".*\""
    SED_REPLACEMENT="\"$new_version\""
    SED_COMMAND="s|$SED_REGEX|$SED_REPLACEMENT|"

    set -e
    set -x

    sed -i -e "/$SED_ADDRESS/ $SED_COMMAND" $file
}

lein-pprint() {
    export PUPPETSERVER_HEAP_SIZE=1G
    "$lein" with-profile dev,ci pprint "$@" | sed -e 's/^"//' -e 's/"$//'
}

git clone https://github.com/puppetlabs/puppetserver

cd puppetserver
git checkout "$PUPPETSERVER_VERSION"
export PUPPETSERVER_HEAP_SIZE=1G
"$lein" install
MAVEN_VER=$(lein-pprint :version)
echo $MAVEN_VER
cd ..

update_dependency_var "project.clj" "puppetserver-version" $MAVEN_VER
