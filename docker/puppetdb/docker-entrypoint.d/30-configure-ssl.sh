#!/bin/sh

master_running() {
    if [ "$CONSUL_ENABLED" = "true" ]; then
      status=$(curl --silent --fail "http://${CONSUL_HOSTNAME}:${CONSUL_PORT}/v1/health/checks/puppet" | grep -q '"Status": "passing"')
      test "$?" = "0"
    else
      status=$(curl --silent --fail --insecure "https://${PUPPETSERVER_HOSTNAME}:8140/status/v1/simple")
      test "$status" = "running"
    fi
}

PUPPETSERVER_HOSTNAME="${PUPPETSERVER_HOSTNAME:-puppet}"
CONSUL_HOSTNAME="${CONSUL_HOSTNAME:-consul}"
CONSUL_PORT="${CONSUL_PORT:-8500}"

if [ ! -f "/etc/puppetlabs/puppet/ssl/certs/${HOSTNAME}.pem" ] && [ "$USE_PUPPETSERVER" = true ]; then
  # if this is our first run, run puppet agent to get certs in place
  while ! master_running; do
    sleep 1
  done
  set -e
  /ssl.sh
fi
if [ ! -d "/etc/puppetlabs/puppetdb/ssl" ] && [ "$USE_PUPPETSERVER" = true ]; then
  /ssl-setup.sh -f
fi
