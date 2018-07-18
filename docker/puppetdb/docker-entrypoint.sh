#!/bin/bash

PUPPETSERVER_HOSTNAME="${PUPPETSERVER_HOSTNAME:-puppet}"
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ]; then
  while ! nc -z "$PUPPETSERVER_HOSTNAME" 8140; do
    sleep 1
  done
  set -e
  /opt/puppetlabs/bin/puppet config set certname "$HOSTNAME"
  /opt/puppetlabs/bin/puppet config set server "$PUPPETSERVER_HOSTNAME"
  /opt/puppetlabs/bin/puppet agent --verbose --onetime --no-daemonize --waitforcert 120
  /opt/puppetlabs/server/bin/puppetdb ssl-setup -f
fi

exec /opt/puppetlabs/server/bin/puppetdb "$@"
