#!/bin/bash

master_running() {
    status=$(curl --silent --fail --insecure "https://${PUPPETSERVER_HOSTNAME}:8140/status/v1/simple")
    test "$status" = "running"
}

PUPPETSERVER_HOSTNAME="${PUPPETSERVER_HOSTNAME:-puppet}"
/opt/puppetlabs/bin/puppet config set certname "$HOSTNAME"
/opt/puppetlabs/bin/puppet config set server "$PUPPETSERVER_HOSTNAME"

if [ ! -f "/etc/puppetlabs/puppet/ssl/certs/${HOSTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  # if this is our first run, run puppet agent to get certs in place
  while ! master_running; do
    sleep 1
  done
  set -e
  /opt/puppetlabs/bin/puppet agent --verbose --onetime --no-daemonize --waitforcert 120
fi
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  /opt/puppetlabs/server/bin/puppetdb ssl-setup -f
fi

exec /opt/puppetlabs/server/bin/puppetdb "$@"
