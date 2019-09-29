#!/bin/sh

set -ex

# Creating log path as a workaround for LCOW VOLUME bug
# https://github.com/moby/moby/issues/39892

# when the log directory doesn't exist, there can be errors from apps logging there
mkdir -p /opt/puppetlabs/server/data/puppetdb/logs
