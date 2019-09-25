#!/bin/sh

# NOTE: define ENV variables and defaults in the Dockerfile

envsub() {
    envsubst < "$1" > "${1}.new"
    mv --force "${1}.new" "$1"
}

envsub /etc/puppetlabs/puppetdb/conf.d/jetty.ini
